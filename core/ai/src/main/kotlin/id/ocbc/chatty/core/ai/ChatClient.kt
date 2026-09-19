package id.ocbc.chatty.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What the chat API knows about an agent, before this app attaches a face and a voice to it. */
data class AgentSummary(val id: String, val displayName: String, val tagline: String)

/** The API answered, and the answer was a refusal. [code] is the API's own machine-readable code. */
class ChatApiException(val status: Int, val code: String?, message: String) : Exception(message)

/**
 * The brain. An OpenAI-compatible chat API whose "models" are personas, each already carrying its
 * own system prompt and customer record server-side.
 */
interface ChatClient {
    /**
     * The personas this account can talk to, as the API's own `/v1/models` list.
     *
     * Each entry is a "model" only in the API's vocabulary — to this app it is an agent with a face
     * and a voice attached elsewhere, so [agents] returns just enough to populate a chooser: an id to
     * ask questions of and the copy to introduce it with.
     */
    suspend fun agents(): List<AgentSummary>

    /**
     * Asks [agentId] the conversation in [history] and emits the answer as it is generated.
     *
     * Each emission is a fragment, not a whole answer — concatenating them in order rebuilds the
     * reply. Streaming is what lets the first sentence reach the synthesizer while the model is
     * still writing the second, which is most of the difference between a companion that answers
     * and one that pauses.
     *
     * [speaking] is the language the app is set to. It is a per-turn parameter rather than part of
     * the persona brief because it changes per turn and the brief is cached; see [languageDirective]
     * for what each client does with it and why the standing prompt could not carry the rule.
     */
    fun reply(agentId: String, history: List<ChatMessage>, speaking: Language): Flow<String>

    /**
     * Fetches whatever this client needs before it can answer for [agentId], so the first turn does
     * not pay for it.
     *
     * Called when a conversation opens, while the customer is still reading the screen and the
     * avatar session is being negotiated — time that is already being spent. A client with nothing
     * to prepare does nothing, which is why this defaults to a no-op rather than being a separate
     * interface nobody could reach through [Brains].
     *
     * Best-effort by contract: failing here must not fail the conversation, because whatever could
     * not be fetched will simply be fetched again on the turn that needs it.
     *
     * ```
     * viewModelScope.launch { brains[brain].warm(agent.id) }   // fire and forget, on open
     * ```
     */
    suspend fun warm(agentId: String) = Unit
}

/**
 * [ChatClient] over `https://kamartaj.xyz`.
 *
 * The bearer key is supplied by the caller rather than read from the environment here, because this
 * module is plain JVM and has no opinion about where an Android app keeps its configuration.
 *
 * ```
 * val chat = HttpChatClient(okHttp, "https://kamartaj.xyz", key)
 * chat.reply("emma", history)
 *     .sentences()
 *     .collect { sentence -> synthesize(sentence) }
 * ```
 */
class HttpChatClient(
    private val calls: Call.Factory,
    private val baseUrl: String,
    private val apiKey: String,
    private val json: Json = DefaultJson,
) : ChatClient {

    override suspend fun agents(): List<AgentSummary> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/v1/models")
            .header("Authorization", "Bearer $apiKey")
            .build()

        calls.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) throw asFailure(response.code, body)
            json.decodeFromString<ModelListDto>(body).data.map {
                AgentSummary(
                    id = it.id,
                    displayName = it.avatar?.displayName ?: it.id.replaceFirstChar(Char::uppercase),
                    tagline = it.avatar?.tagline.orEmpty(),
                )
            }
        }
    }

    /**
     * Reads the Server-Sent Events stream the API returns for `stream: true`.
     *
     * The wire format is OpenAI's: a run of `data: {…}` lines, each carrying a delta, terminated by
     * the literal `data: [DONE]`. Anything else on the stream — comments, blank keep-alive lines, a
     * chunk with no content — is skipped rather than failed on, because none of it changes the
     * answer and a strict reader would drop a live turn over a heartbeat.
     */
    override fun reply(agentId: String, history: List<ChatMessage>, speaking: Language): Flow<String> = flow {
        val payload = ChatRequestDto(
            model = agentId,
            // This persona already knows who it is, so the only thing prepended is the one rule
            // that cannot live server-side: which language to answer this question in.
            messages = listOf(MessageDto(ChatMessage.Role.SYSTEM.wire, languageDirective(speaking))) +
                history.map { MessageDto(it.role.wire, it.content) },
            stream = true,
        )
        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "text/event-stream")
            .post(json.encodeToString(payload).toRequestBody(JsonMedia))
            .build()

        var emitted = false
        calls.newCall(request).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful) throw asFailure(response.code, body.string())

            val source = body.source()
            while (true) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith(DATA_PREFIX)) continue
                val data = line.removePrefix(DATA_PREFIX).trim()
                if (data == DONE_SENTINEL) break

                val delta = runCatching {
                    json.decodeFromString<ChatChunkDto>(data).choices.firstOrNull()?.delta?.content
                }.getOrNull()
                if (!delta.isNullOrEmpty()) {
                    emit(delta)
                    emitted = true
                }
            }
        }
        if (!emitted) throw ChatApiException(NO_CONTENT_STATUS, null, "the agent returned an empty answer")
    }.flowOn(Dispatchers.IO)

    /**
     * Turns an error body into an exception carrying the API's own message.
     *
     * The API is OpenAI-shaped, so a failure is `{"error":{"message":…,"code":…}}` and that message
     * is the useful one — "The model `nope` does not exist. Available models: …" says more than any
     * status line this client could invent. A body that does not parse falls back to the status.
     */
    private fun asFailure(status: Int, body: String): ChatApiException {
        val error = runCatching { json.decodeFromString<ErrorEnvelopeDto>(body).error }.getOrNull()
        return ChatApiException(
            status = status,
            code = error?.code,
            message = error?.message ?: "chat API returned HTTP $status",
        )
    }

    private companion object {
        val JsonMedia = "application/json; charset=utf-8".toMediaType()

        const val DATA_PREFIX = "data:"
        const val DONE_SENTINEL = "[DONE]"

        /**
         * The status on a stream that carried no content. Not an HTTP code the server sent — the
         * transfer succeeded — so it is distinct from any real one, and a caller branching on
         * [ChatApiException.status] can tell "the API refused" from "the API said nothing".
         */
        const val NO_CONTENT_STATUS = 0
    }
}

/**
 * Lenient because the API adds fields (`x_avatar`, `usage`, …) faster than this client needs them,
 * and a new one is never a reason to fail a turn.
 */
val DefaultJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
private data class ModelListDto(val data: List<ModelDto> = emptyList())

@Serializable
private data class ModelDto(
    val id: String,
    @SerialName("x_avatar") val avatar: AvatarMetaDto? = null,
)

@Serializable
private data class AvatarMetaDto(
    @SerialName("display_name") val displayName: String? = null,
    val tagline: String? = null,
)

@Serializable
private data class ChatRequestDto(
    val model: String,
    val messages: List<MessageDto>,
    val stream: Boolean = false,
)

@Serializable
private data class MessageDto(val role: String, val content: String)

@Serializable
private data class ChatChunkDto(val choices: List<ChunkChoiceDto> = emptyList())

@Serializable
private data class ChunkChoiceDto(val delta: DeltaDto? = null)

@Serializable
private data class DeltaDto(val content: String? = null)

@Serializable
private data class ErrorEnvelopeDto(val error: ErrorDto? = null)

@Serializable
private data class ErrorDto(val message: String? = null, val code: String? = null)
