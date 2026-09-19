package id.ocbc.chatty.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource

/**
 * Answers a turn from a model this app briefs itself, rather than one that already knows the
 * customer.
 *
 * # What these add over [HttpChatClient]
 *
 * Two things, and only two: a system prompt built from [advisorPrompt] and the persona's customer
 * record, and the vendor's own wire format. Everything after the fragments — clause splitting,
 * synthesis, the avatar, captions — is unchanged, because it only ever sees a `Flow<String>`.
 *
 * [agents] is not theirs to answer. The roster of personas belongs to the demo API, so it is
 * delegated to whichever client owns it; a frontier model has no opinion about who Daniel is.
 */
abstract class BriefedChatClient(
    private val roster: ChatClient,
    private val records: CustomerRecords,
) : ChatClient {

    /**
     * Briefs already built, by persona id.
     *
     * A brief is two network round trips — the roster and the customer record — and both answer the
     * same thing every time for a given persona. Built on every turn they were 330 ms of dead wait
     * in front of the model call, measured, before a byte of the question went out. Nothing in a
     * brief changes while the app is running, so the first turn pays for it and the rest do not.
     *
     * The consequence to know about: a customer record edited server-side mid-session is not picked
     * up until the process restarts. That is the right trade for demo data that does not move, and
     * the wrong one for a record that does — this is the line to delete if that ever changes.
     */
    private val briefs = mutableMapOf<String, String>()

    /**
     * Guards [briefs] across the fetch, not just the map write.
     *
     * Held for the whole build so two turns racing on the same cold persona make one pair of
     * requests rather than two. Turns are serialised upstream today, so this never actually
     * contends; it is here because a cache that can double-fetch under a race is a cache that will.
     */
    private val briefing = Mutex()

    override suspend fun agents(): List<AgentSummary> = roster.agents()

    override suspend fun warm(agentId: String) {
        runCatching { brief(agentId) }
    }

    /** The system prompt for [agentId], fetched on first use and reused after that. */
    protected suspend fun brief(agentId: String): String = briefing.withLock {
        briefs[agentId] ?: build(agentId).also { briefs[agentId] = it }
    }

    /** Fetches the roster entry and the customer record [agentId] advises on, and renders a prompt. */
    private suspend fun build(agentId: String): String {
        val summary = roster.agents().firstOrNull { it.id == agentId }
        return advisorPrompt(
            displayName = summary?.displayName ?: agentId.replaceFirstChar(Char::uppercase),
            tagline = summary?.tagline.orEmpty(),
            customerRecordJson = records.of(agentId),
        )
    }
}

/**
 * [ChatClient] over OpenAI's chat completions.
 *
 * The wire format is the one [HttpChatClient] already speaks — the demo API is OpenAI-compatible —
 * so the only differences are the host, the key, a real model id in place of the persona, and the
 * system prompt riding as the first message.
 */
class OpenAiChatClient(
    private val calls: Call.Factory,
    private val apiKey: String,
    private val model: String = OPENAI_MODEL,
    private val reasoningEffort: String? = OPENAI_REASONING_EFFORT,
    private val baseUrl: String = OPENAI_BASE_URL,
    private val json: Json = DefaultJson,
    roster: ChatClient,
    records: CustomerRecords,
) : BriefedChatClient(roster, records) {

    override fun reply(agentId: String, history: List<ChatMessage>, speaking: Language): Flow<String> = flow {
        val messages = listOf(
            OpenAiMessageDto("system", brief(agentId)),
            // Its own message, after the brief. The brief is identical every turn and is what the
            // provider is asked to cache; a per-turn string folded into it would spoil that.
            OpenAiMessageDto("system", languageDirective(speaking)),
        ) +
            history.map { OpenAiMessageDto(it.role.wire, it.content) }

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(
                json.encodeToString(OpenAiRequestDto(model, messages, stream = true, reasoningEffort = reasoningEffort))
                    .toRequestBody(JsonMedia),
            )
            .build()

        var emitted = false
        calls.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ChatApiException(response.code, null, failure("OpenAI", response.code, response.body.string()))
            }
            response.body.source().forEachDataLine { data ->
                val delta = runCatching {
                    json.decodeFromString<OpenAiChunkDto>(data).choices.firstOrNull()?.delta?.content
                }.getOrNull()
                if (!delta.isNullOrEmpty()) {
                    emit(delta)
                    emitted = true
                }
            }
        }
        if (!emitted) throw ChatApiException(0, null, "the model returned an empty answer")
    }.flowOn(Dispatchers.IO)
}

/**
 * [ChatClient] over Anthropic's Messages API.
 *
 * Three things differ from the OpenAI shape and all three are easy to get wrong: the key rides
 * `x-api-key` rather than a bearer, the API version is a required header, and the system prompt is a
 * top-level field instead of a message. The stream is still `data:` lines, but each one is a typed
 * event and only `content_block_delta` carries text.
 */
class AnthropicChatClient(
    private val calls: Call.Factory,
    private val apiKey: String,
    private val model: String = ANTHROPIC_MODEL,
    private val baseUrl: String = ANTHROPIC_BASE_URL,
    private val json: Json = DefaultJson,
    roster: ChatClient,
    records: CustomerRecords,
) : BriefedChatClient(roster, records) {

    override fun reply(agentId: String, history: List<ChatMessage>, speaking: Language): Flow<String> = flow {
        val payload = AnthropicRequestDto(
            model = model,
            maxTokens = MAX_TOKENS,
            // One block rather than a bare string, because a block is the only thing a cache
            // breakpoint can be attached to. See [AnthropicSystemBlockDto].
            system = listOf(
                AnthropicSystemBlockDto(
                    type = BLOCK_TEXT,
                    text = brief(agentId),
                    cacheControl = CacheControlDto(CACHE_EPHEMERAL),
                ),
                // Deliberately after the breakpoint, and deliberately uncached. Everything up to a
                // `cache_control` block is what gets reused, so a string that changes per turn has
                // to sit past it or every question pays full price for the brief again.
                AnthropicSystemBlockDto(type = BLOCK_TEXT, text = languageDirective(speaking)),
            ),
            messages = history.map { AnthropicMessageDto(it.role.wire, it.content) },
            thinking = AnthropicThinkingDto(THINKING_DISABLED),
            stream = true,
        )
        val request = Request.Builder()
            .url("$baseUrl/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", ANTHROPIC_VERSION)
            .header("Accept", "text/event-stream")
            .post(json.encodeToString(payload).toRequestBody(JsonMedia))
            .build()

        var emitted = false
        calls.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw ChatApiException(
                    response.code,
                    null,
                    failure("Anthropic", response.code, response.body.string()),
                )
            }
            response.body.source().forEachDataLine { data ->
                val text = runCatching {
                    json.decodeFromString<AnthropicEventDto>(data).delta?.text
                }.getOrNull()
                if (!text.isNullOrEmpty()) {
                    emit(text)
                    emitted = true
                }
            }
        }
        if (!emitted) throw ChatApiException(0, null, "the model returned an empty answer")
    }.flowOn(Dispatchers.IO)
}

/**
 * Walks a Server-Sent Events body, handing [onData] the payload of every `data:` line.
 *
 * Shared because both vendors frame their streams the same way even though what is inside differs.
 * Comments, blank keep-alives and `event:` lines are skipped rather than failed on: none of them
 * change the answer, and a strict reader would drop a live turn over a heartbeat.
 */
private inline fun BufferedSource.forEachDataLine(onData: (String) -> Unit) {
    while (true) {
        val line = readUtf8Line() ?: break
        if (!line.startsWith("data:")) continue
        val data = line.removePrefix("data:").trim()
        if (data.isEmpty() || data == "[DONE]") continue
        onData(data)
    }
}

/** The vendor's own message if it sent one, because it says more than a status line ever does. */
private fun failure(vendor: String, status: Int, body: String): String =
    "$vendor returned HTTP $status: ${body.take(240).trim()}"

private val JsonMedia = "application/json; charset=utf-8".toMediaType()

const val OPENAI_BASE_URL = "https://api.openai.com"
const val OPENAI_MODEL = "gpt-5"

/**
 * How much GPT-5 is allowed to think before it answers.
 *
 * Left at its default it reasons first, and measured against this app's own prompt that cost 512
 * reasoning tokens and **8.5 seconds** before a single word came back — through the full pipeline it
 * was 19 seconds to the first token and 21 to the lips moving. The customer is sitting in front of a
 * face waiting for it to talk. At "minimal" the same question answers in 2.4 seconds with no
 * reasoning tokens at all.
 *
 * This is the right trade for the job: a relationship manager reading figures off a record and
 * talking about them is recall and phrasing, not deliberation. Raise it for a use that genuinely
 * reasons, and expect the wait back.
 */
const val OPENAI_REASONING_EFFORT = "minimal"

const val ANTHROPIC_BASE_URL = "https://api.anthropic.com"
const val ANTHROPIC_MODEL = "claude-sonnet-5"
private const val ANTHROPIC_VERSION = "2023-06-01"

/**
 * Every value below is written out at the call site rather than defaulted on the DTO.
 *
 * [DefaultJson] leaves `encodeDefaults` off, so a field whose value equals its declared default is
 * dropped from the request body — silently, and the API then applies *its* default instead. That is
 * how `thinking` and `cache_control` can be written, compile, and never leave the handset.
 */
private const val BLOCK_TEXT = "text"
private const val CACHE_EPHEMERAL = "ephemeral"
private const val THINKING_DISABLED = "disabled"

/** Answers here are spoken, so they are short by instruction; this is only a runaway guard. */
private const val MAX_TOKENS = 1024

@Serializable
private data class OpenAiRequestDto(
    val model: String,
    val messages: List<OpenAiMessageDto>,
    val stream: Boolean = false,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
)

@Serializable
private data class OpenAiMessageDto(val role: String, val content: String)

@Serializable
private data class OpenAiChunkDto(val choices: List<OpenAiChoiceDto> = emptyList())

@Serializable
private data class OpenAiChoiceDto(val delta: OpenAiDeltaDto? = null)

@Serializable
private data class OpenAiDeltaDto(val content: String? = null)

@Serializable
private data class AnthropicRequestDto(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: List<AnthropicSystemBlockDto>,
    val messages: List<AnthropicMessageDto>,
    val thinking: AnthropicThinkingDto,
    val stream: Boolean = false,
)

/**
 * A block of the system prompt, and optionally a cache breakpoint on the end of it.
 *
 * The whole brief — the advisor rules and the customer's record — is one block because all of it is
 * identical from one turn to the next; the question that differs rides in `messages`, after it.
 * That is what makes the prefix reusable: caching is a prefix match, so anything volatile placed
 * ahead of the breakpoint would invalidate everything behind it on every turn.
 */
@Serializable
private data class AnthropicSystemBlockDto(
    val type: String,
    val text: String,
    @SerialName("cache_control") val cacheControl: CacheControlDto? = null,
)

/**
 * Marks the end of the reusable prefix.
 *
 * Measured on this app's own prompt: 5,422 input tokens, re-read in full on every turn. Cached they
 * are billed at a tenth of that from the second turn on, which is the whole of why this is here —
 * it did **not** move time to the first token (2,865 ms cached against 2,822 ms not), so it is a
 * cost fix and should not be sold as a speed one. The default five-minute window outlives the gap
 * between questions in a live conversation, and every read pushes the expiry out again.
 */
@Serializable
private data class CacheControlDto(val type: String)

/**
 * How much the model deliberates before it starts writing. Off.
 *
 * Sonnet 5 thinks adaptively when this field is absent, and absent is what it was: measured through
 * this app's own prompt that cost **2,822–3,230 ms** before the first word, against **1,201 ms**
 * with it disabled. Through the full pipeline that is most of two seconds of a face sitting still.
 *
 * It is the same trade already made for GPT-5 in [OPENAI_REASONING_EFFORT] and it is the same
 * reasoning: an adviser reading figures off a record they were handed is doing recall and phrasing,
 * not deliberation. Turn it back on (`"adaptive"`) for work that genuinely reasons, and expect the
 * wait back. `{"type": "adaptive"}` with `output_config.effort` at `low` is the middle setting —
 * 1,521 ms measured — if an answer ever needs the thinking back but not all of it.
 */
@Serializable
private data class AnthropicThinkingDto(val type: String)

@Serializable
private data class AnthropicMessageDto(val role: String, val content: String)

@Serializable
private data class AnthropicEventDto(val type: String? = null, val delta: AnthropicDeltaDto? = null)

@Serializable
private data class AnthropicDeltaDto(val type: String? = null, val text: String? = null)
