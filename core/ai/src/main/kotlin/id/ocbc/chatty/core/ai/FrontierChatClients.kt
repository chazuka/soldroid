package id.ocbc.chatty.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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

    override suspend fun agents(): List<AgentSummary> = roster.agents()

    /** Builds the system prompt for [agentId], which means fetching the customer it advises. */
    protected suspend fun brief(agentId: String): String {
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

    override fun reply(agentId: String, history: List<ChatMessage>): Flow<String> = flow {
        val messages = listOf(OpenAiMessageDto("system", brief(agentId))) +
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

    override fun reply(agentId: String, history: List<ChatMessage>): Flow<String> = flow {
        val payload = AnthropicRequestDto(
            model = model,
            maxTokens = MAX_TOKENS,
            system = brief(agentId),
            messages = history.map { AnthropicMessageDto(it.role.wire, it.content) },
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
    val system: String,
    val messages: List<AnthropicMessageDto>,
    val stream: Boolean = false,
)

@Serializable
private data class AnthropicMessageDto(val role: String, val content: String)

@Serializable
private data class AnthropicEventDto(val type: String? = null, val delta: AnthropicDeltaDto? = null)

@Serializable
private data class AnthropicDeltaDto(val type: String? = null, val text: String? = null)
