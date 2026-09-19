package id.ocbc.chatty.core.avatar

import android.util.Log
import java.io.IOException
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** What the client needs to join the LiveKit room LiveAvatar publishes the rendered face into. */
data class AvatarStream(val livekitUrl: String, val livekitToken: String)

/** Something the provider said that the app acts on. Everything else is read and dropped. */
sealed interface LiveAvatarEvent {
    /** The provider reports the session connected; commands will now be honoured. */
    data object Connected : LiveAvatarEvent

    /** The avatar's lips started moving. This is the moment the customer's wait actually ends. */
    data object SpeakStarted : LiveAvatarEvent

    /** The avatar finished the utterance. The authoritative end of a turn. */
    data object SpeakEnded : LiveAvatarEvent

    /**
     * The renderer ran out of audio mid-utterance and the avatar stalled. The specific failure that
     * streaming synthesis can cause and buffering could not, so it is named rather than lumped in.
     */
    data object Starved : LiveAvatarEvent

    /** The provider reported a fault over a socket that is still up. [reason] is its own words. */
    data class Failed(val reason: String) : LiveAvatarEvent

    /**
     * The socket is gone and this session can never speak again.
     *
     * Deliberately distinct from [Failed]. A provider fault arrives *over* a working connection and
     * the session may well carry the next utterance; a dead socket cannot carry anything, and a
     * caller that cannot tell the two apart will keep a corpse and spend a turn discovering it. That
     * is exactly what happened after a network drop: the next question failed, and only then was the
     * session replaced — one wasted turn, visible to the customer as an error they did not cause.
     */
    data object Dead : LiveAvatarEvent
}

/** The provider refused, or the socket is gone. */
class LiveAvatarException(message: String) : IOException(message)

/**
 * One utterance that has been handed to the provider in full.
 *
 * Sending the last frame is not the end of speaking — the provider still has seconds of audio to
 * render — so this exists to let the caller wait for the *real* end without inventing a duration.
 */
class Utterance internal constructor(
    /** Every PCM byte sent. The provider will take about this long to say it, and no less. */
    val bytesSent: Long,
    private val ended: CompletableDeferred<Unit>,
) {
    /** How long the audio itself runs, from its own size. 24 kHz, 16-bit, mono. */
    val expectedDurationMs: Long = bytesSent * MILLIS_PER_SECOND / BYTES_PER_SECOND

    /**
     * Waits for the provider's `agent.speak_ended`, or gives up once the audio cannot still be
     * playing.
     *
     * The bound is derived rather than fixed, because a one-line answer and a six-sentence one are
     * not the same wait. Returning false means the provider stopped reporting: the turn must still
     * end, or the composer stays disabled over a socket that has gone quiet.
     */
    suspend fun awaitEnd(graceMs: Long = DEFAULT_GRACE_MS): Boolean =
        withTimeoutOrNull(expectedDurationMs + graceMs) { ended.await() } != null

    private companion object {
        const val BYTES_PER_SECOND = 24_000L * 2
        const val MILLIS_PER_SECOND = 1_000L

        /** Room for the provider's own render lag on top of the audio's length. */
        const val DEFAULT_GRACE_MS = 10_000L
    }
}

/**
 * One LiveAvatar LITE session: the HTTP handshake, the command socket, and the speaking.
 *
 * # Why LITE, and why this app synthesizes its own audio
 *
 * LITE's `agent.speak` command carries *audio*, not text — there is no speak-text command at all.
 * That is why [id.ocbc.chatty.core.ai.SpeechSynthesizer] exists: the provider is handed 16-bit
 * samples and never sees a word, so it cannot reinterpret, rewrite, or add to what the agent said.
 * FULL mode would hand the provider the microphone and an LLM of its own; this app never asks for it.
 *
 * # Lifetime
 *
 * One instance is one session. [open] is not idempotent — call it once, [close] once, and build a
 * new instance to switch agents, because the avatar id is fixed when the session token is minted.
 * A session also expires on its own: see [expiresAtMs].
 *
 * ```
 * val session = LiveAvatarSession(http, apiKey, scope)
 * val stream = session.open(agent.avatar.avatarId)
 * controller.attach(stream)
 *
 * val utterance = session.speak(sentences.flatMapConcat { synthesizer.speak(voiceId, it) })
 * utterance.awaitEnd()
 * session.close()
 * ```
 */
class LiveAvatarSession(
    private val http: OkHttpClient,
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val baseUrl: String = LIVEAVATAR_BASE_URL,
    private val json: Json = LenientJson,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val _events = MutableSharedFlow<LiveAvatarEvent>(extraBufferCapacity = 16)

    /** What the provider has reported on this session's socket — see [LiveAvatarEvent]. */
    val events: SharedFlow<LiveAvatarEvent> = _events.asSharedFlow()

    /** Serialises utterances. Two interleaving their frames on one socket render as one garbled line. */
    private val speaking = Mutex()

    private val connected = CompletableDeferred<Unit>()

    /**
     * When the provider will end this session on its own, as wall-clock milliseconds.
     *
     * LiveAvatar caps a session — 300 seconds on this account — and a conversation routinely outlives
     * that. Publishing the deadline lets a caller re-open *before* the face dies rather than after,
     * which is the difference between a seamless demo and one that drops out at the five-minute mark.
     */
    @Volatile var expiresAtMs: Long = Long.MAX_VALUE
        private set

    private var sessionToken: String? = null
    private var providerSessionId: String? = null
    private var socket: WebSocket? = null
    private var keepAlive: Job? = null

    /** The utterance currently being rendered, completed by `agent.speak_ended`. */
    @Volatile private var pending: CompletableDeferred<Unit>? = null

    /**
     * Opens a session for [avatarId] and returns the room material for the client to subscribe to.
     *
     * Blocks for up to [READY_TIMEOUT_MS] waiting for the provider to report the session connected.
     * It degrades rather than fails: a frame sent before that report *can* be dropped — which looks
     * like an avatar silently skipping its first line — but not every deployment emits the event, and
     * refusing to speak would turn an unproven expectation into an outage on a path that works.
     */
    suspend fun open(avatarId: String): AvatarStream {
        val tokenRequest = TokenRequestDto(
            mode = MODE_LITE,
            avatarId = avatarId,
            videoSettings = VideoSettingsDto(quality = VIDEO_QUALITY, encoding = VIDEO_ENCODING),
        )
        val token = json.decodeFromJsonElement<TokenDto>(
            post(PATH_SESSION_TOKEN, bearer = null, body = json.encodeToString(tokenRequest)),
        )
        sessionToken = token.sessionToken

        // `start` takes no body: the bearer token identifies the session it is starting.
        val start = json.decodeFromJsonElement<StartDto>(
            post(PATH_SESSION_START, bearer = token.sessionToken, body = "{}"),
        )
        providerSessionId = start.sessionId
        if (start.wsUrl.isNullOrBlank() || start.livekitUrl.isNullOrBlank() ||
            start.livekitClientToken.isNullOrBlank()
        ) {
            close()
            throw LiveAvatarException("$PATH_SESSION_START returned an incomplete session")
        }
        start.maxSessionDuration?.takeIf { it > 0 }?.let {
            expiresAtMs = nowMs() + it * MILLIS_PER_SECOND
        }

        socket = http.newWebSocket(
            Request.Builder()
                .url(start.wsUrl)
                .header("Authorization", "Bearer ${token.sessionToken}")
                .build(),
            Listener(),
        )
        keepAlive = scope.launch {
            // LiveAvatar documents a 5-minute idle timeout on the LITE socket. 30 s is an order of
            // magnitude inside it, which survives a missed tick or a slow network.
            while (true) {
                delay(KEEP_ALIVE_MS)
                // A dead socket ends the keep-alive, it does not crash the app: `send` throws, and
                // this coroutine runs on a scope that outlives the screen, where an escaping
                // exception has nobody left to report it to. `speak` discovers the same socket on
                // the next turn and re-opens.
                if (runCatching { send(CommandDto(CMD_KEEP_ALIVE)) }.isFailure) return@launch
            }
        }

        // Deliberately does NOT wait for the provider to report "connected". The LiveKit material is
        // already valid, and joining that room takes ~1.8 s of its own — time that used to be spent
        // *after* a ~2.5 s wait rather than during it. Returning now lets the two overlap, which is
        // most of the gap between tapping an agent and seeing a face.
        //
        // Nothing is lost: readiness only matters before the first `agent.speak`, and [speak] waits
        // for it there instead.
        return AvatarStream(livekitUrl = start.livekitUrl, livekitToken = start.livekitClientToken)
    }

    /**
     * Streams one utterance and seals it.
     *
     * Synthesis and transmission interleave — [frames] is collected as it renders — so the customer
     * waits for the first sample rather than the last. When the flow is itself built from a
     * sentence-by-sentence synthesis, the avatar starts on sentence one while the model is still
     * writing sentence three, and the whole answer still arrives as a single unbroken utterance
     * because `agent.speak_end` is sent once, at the end.
     *
     * A failure part-way through leaves audio buffered provider-side that would lip-sync as a
     * truncated sentence, so it is cleared with `agent.interrupt` rather than left to play.
     *
     * @param onFirstFrame called once, before the first frame is encoded; where the avatar leg starts.
     * @param onFirstFrameSent called once, after that frame has been encoded and handed to the
     *   socket. The pair brackets this app's own share of the avatar leg — base64 and JSON — so a
     *   slow first word can be blamed on the right side of the wire. It brackets encoding only:
     *   `WebSocket.send` enqueues and returns, so the far end is not waited for here.
     */
    suspend fun speak(
        frames: Flow<ByteArray>,
        onFirstFrame: () -> Unit = {},
        onFirstFrameSent: () -> Unit = {},
    ): Utterance =
        speaking.withLock {
            // The wait [open] used to do, moved to the one place it actually matters. A frame sent
            // before the provider reports the session connected can be dropped, which shows up as an
            // avatar silently skipping its first line.
            //
            // It degrades rather than fails: the live capture this was built against recorded
            // speak_started through idle_started but never a session-state event, so it is not
            // certain every deployment emits one — and refusing to speak would turn an unproven
            // expectation into an outage on a path that works today.
            if (withTimeoutOrNull(READY_TIMEOUT_MS) { connected.await() } == null) {
                Log.w(TAG, "provider never reported the session connected; speaking anyway")
            }

            val ended = CompletableDeferred<Unit>()
            pending = ended

            var sent = 0L
            try {
                frames.collect { frame ->
                    val first = sent == 0L
                    if (first) onFirstFrame()
                    send(CommandDto(CMD_SPEAK, audio = Base64.getEncoder().encodeToString(frame)))
                    if (first) onFirstFrameSent()
                    sent += frame.size
                }
            } catch (e: Exception) {
                if (sent > 0L) runCatching { send(CommandDto(CMD_INTERRUPT)) }
                pending = null
                throw e
            }
            if (sent == 0L) {
                pending = null
                throw LiveAvatarException("refusing to seal an utterance with no audio")
            }
            send(CommandDto(CMD_SPEAK_END))
            // What is still sitting in OkHttp's buffer once the whole answer has been handed over.
            //
            // The one thing the timing marks cannot see. They bracket encoding, because `send`
            // enqueues and returns, so they would read fast on a handset whose uplink is minutes
            // behind. A queue that is empty here means the audio really did leave as it was made; a
            // queue holding most of the utterance means a slow first word is this connection, and
            // no amount of tuning the provider will move it.
            Log.i(TAG, "sealed ${sent}B utterance, ${socket?.queueSize() ?: 0}B still queued")
            Utterance(sent, ended)
        }

    /**
     * Drops whatever the avatar has buffered but not yet said.
     *
     * This is barge-in: the customer has seen enough and wants the rest of the answer to stop. The
     * pending utterance is completed rather than abandoned, so whoever is waiting on it is released
     * immediately instead of sitting out the remaining audio's worth of timeout.
     */
    fun interrupt() {
        runCatching { send(CommandDto(CMD_INTERRUPT)) }
        pending?.complete(Unit)
        pending = null
    }

    /** Stops the socket and the billed provider session. Safe to call more than once. */
    suspend fun close() {
        keepAlive?.cancel()
        keepAlive = null
        pending?.complete(Unit)
        pending = null
        socket?.close(WS_NORMAL_CLOSURE, STOP_USER_CLOSED)
        socket = null

        val token = sessionToken ?: return
        val id = providerSessionId
        sessionToken = null
        providerSessionId = null
        if (id != null) {
            // A session we can no longer reach is a session we no longer have; the provider's own
            // idle timeout is the backstop. Failing here must not mask the reason we are closing.
            val stop = json.encodeToString(StopRequestDto(id, STOP_USER_CLOSED))
            runCatching { post(PATH_SESSION_STOP, bearer = token, body = stop) }
        }
    }

    private fun send(command: CommandDto) {
        val open = socket?.send(json.encodeToString(command)) ?: false
        if (!open) throw LiveAvatarException("the LITE socket is closed")
    }

    /**
     * Reading is not optional even though every command is fire-and-forget: OkHttp answers pings and
     * observes close frames only while a listener is attached, and the provider's lifecycle events —
     * `agent.speak_ended` above all — arrive nowhere else.
     *
     * Event names are matched by suffix. The provider namespaces them (`agent.speak_ended`), and a
     * vendor that renames the namespace should not silently strip this app of its turn-end signal.
     */
    private inner class Listener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = runCatching { json.decodeFromString<ServerEventDto>(text) }.getOrNull() ?: return
            val type = event.type
            when {
                type.endsWith(EVT_SESSION_STATE) && event.state == STATE_CONNECTED -> {
                    connected.complete(Unit)
                    _events.tryEmit(LiveAvatarEvent.Connected)
                }

                type.endsWith(EVT_SPEAK_STARTED) -> _events.tryEmit(LiveAvatarEvent.SpeakStarted)

                type.endsWith(EVT_SPEAK_ENDED) -> {
                    pending?.complete(Unit)
                    pending = null
                    _events.tryEmit(LiveAvatarEvent.SpeakEnded)
                }

                type == EVT_ERROR -> if (event.error?.type == ERR_VIDEO_STARVATION) {
                    _events.tryEmit(LiveAvatarEvent.Starved)
                } else {
                    _events.tryEmit(LiveAvatarEvent.Failed(event.error?.message ?: "provider error"))
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Release anyone still waiting on a session that will never answer again.
            connected.complete(Unit)
            pending?.complete(Unit)
            pending = null
            _events.tryEmit(LiveAvatarEvent.Failed(t.message ?: "lite socket failed"))
            _events.tryEmit(LiveAvatarEvent.Dead)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            connected.complete(Unit)
            pending?.complete(Unit)
            pending = null
            // A clean close is still a close: the provider reaps a session at its own cap, and this
            // one cannot speak again either.
            _events.tryEmit(LiveAvatarEvent.Dead)
        }
    }

    /** Posts one JSON body and returns the `data` element of LiveAvatar's response envelope. */
    private suspend fun post(path: String, bearer: String?, body: String): JsonElement =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(baseUrl + path)
                .post(body.toRequestBody(JsonMedia))
            // The account key opens a session; the session token does everything after.
            if (bearer != null) {
                builder.header("Authorization", "Bearer $bearer")
            } else {
                builder.header("X-API-KEY", apiKey)
            }

            http.newCall(builder.build()).execute().use { response ->
                val payload = response.body.string()
                if (!response.isSuccessful) {
                    throw LiveAvatarException(
                        "$path returned HTTP ${response.code}: ${payload.take(ERROR_SNIPPET_CHARS).trim()}",
                    )
                }
                // Every LiveAvatar response is {"code":…,"data":…,"message":…}.
                val envelope = json.decodeFromString<EnvelopeDto>(payload)
                envelope.data
                    ?: throw LiveAvatarException("$path returned no data: ${envelope.message}")
            }
        }

    companion object {
        private const val TAG = "chatty.avatar"

        const val LIVEAVATAR_BASE_URL = "https://api.liveavatar.com"

        private const val PATH_SESSION_TOKEN = "/v1/sessions/token"
        private const val PATH_SESSION_START = "/v1/sessions/start"
        private const val PATH_SESSION_STOP = "/v1/sessions/stop"

        /**
         * The only mode this app will ever request. FULL mode would have the provider run ASR over
         * the microphone and drive an LLM of its own — a second brain, answering from outside every
         * rule this app holds. There is no configuration for this on purpose.
         */
        private const val MODE_LITE = "LITE"

        private const val CMD_SPEAK = "agent.speak"
        private const val CMD_SPEAK_END = "agent.speak_end"
        private const val CMD_KEEP_ALIVE = "session.keep_alive"
        private const val CMD_INTERRUPT = "agent.interrupt"

        private const val EVT_SESSION_STATE = "state_updated"
        private const val EVT_SPEAK_STARTED = "speak_started"
        private const val EVT_SPEAK_ENDED = "speak_ended"
        private const val EVT_ERROR = "error"
        private const val STATE_CONNECTED = "connected"
        private const val ERR_VIDEO_STARVATION = "video_starvation"

        private const val STOP_USER_CLOSED = "USER_CLOSED"

        /**
         * `low`, `medium`, `high` or `very_high`. This is the one setting that actually moves:
         * [VIDEO_ENCODING] matches what the provider would have chosen anyway, so until this commit
         * the room had been running at whatever LiveAvatar defaults to rather than at `high`.
         *
         * More pixels is more bitrate through the same handset connection, and a renderer that runs
         * short of data is a mouth that stalls mid-word — see [LiveAvatarEvent.Starved], which the
         * turn trace counts. If the face is sharp on wifi and breaking up on 4G, this is the knob,
         * and it only turns downwards: `very_high` is 1080p, which this account's plan refuses with
         * `4030 — 1080p sessions require a Business or Enterprise plan`. `high` is the ceiling here.
         */
        private const val VIDEO_QUALITY = "high"

        /** H264. VP8 is deprecated at the provider; this is also their default, so it pins rather than changes. */
        private const val VIDEO_ENCODING = "H264"

        private const val KEEP_ALIVE_MS = 30_000L
        private const val READY_TIMEOUT_MS = 3_000L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val WS_NORMAL_CLOSURE = 1000
        private const val ERROR_SNIPPET_CHARS = 512

        private val JsonMedia = "application/json; charset=utf-8".toMediaType()

        /** The provider adds events and fields; an unrecognised one must never drop a live session. */
        val LenientJson: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

@Serializable
private data class EnvelopeDto(
    val code: Int = 0,
    val data: JsonElement? = null,
    val message: String = "",
)

@Serializable
private data class TokenRequestDto(
    val mode: String,
    @SerialName("avatar_id") val avatarId: String,
    @SerialName("video_settings") val videoSettings: VideoSettingsDto,
)

/**
 * Named rather than left to the provider's defaults: the whole demo is made through a face on a
 * phone screen, so this is the cheapest quality lever there is. H264 is the only non-deprecated
 * codec — VP8 is deprecated — and pinning it here means a change of vendor default cannot quietly
 * change what the room sees.
 *
 * # Why there are no default values here
 *
 * There were, and they were the bug. [LenientJson] leaves `encodeDefaults` off, so a field whose
 * value equals its declared default is dropped from the request body — silently. Built as
 * `VideoSettingsDto()`, every field was its own default, and what actually went out was
 * `"video_settings":{}`: the provider applied its own settings for the entire life of this file,
 * while the comment above said they were pinned. Passing them at the call site is what makes the
 * paragraph above true.
 */
@Serializable
private data class VideoSettingsDto(
    val quality: String,
    val encoding: String,
)

@Serializable
private data class TokenDto(
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("session_token") val sessionToken: String,
)

@Serializable
private data class StartDto(
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("livekit_url") val livekitUrl: String? = null,
    @SerialName("livekit_client_token") val livekitClientToken: String? = null,
    @SerialName("ws_url") val wsUrl: String? = null,
    @SerialName("max_session_duration") val maxSessionDuration: Long? = null,
)

@Serializable
private data class StopRequestDto(
    @SerialName("session_id") val sessionId: String,
    val reason: String,
)

@Serializable
private data class CommandDto(val type: String, val audio: String? = null)

@Serializable
private data class ServerEventDto(
    val type: String = "",
    val state: String? = null,
    val error: ServerErrorDto? = null,
)

@Serializable
private data class ServerErrorDto(val type: String? = null, val message: String? = null)
