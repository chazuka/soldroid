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
import okio.Buffer

/** The synthesizer refused, or stopped mid-utterance. */
class SynthesisException(message: String) : Exception(message)

/**
 * Turns a line of text into the audio the avatar lip-syncs to.
 *
 * The contract is deliberately narrow — frames of PCM, in order — because that is exactly what
 * LiveAvatar's LITE socket accepts, and anything richer would invite a transcoding step that this
 * pairing exists to avoid.
 */
interface SpeechSynthesizer {
    /**
     * Synthesizes [text] in [voiceId] and emits it as it renders.
     *
     * Each emission is PCM signed 16-bit little-endian, 24 kHz, mono — one second per frame except
     * the last. The flow is cold: nothing is requested, and nothing is billed, until it is collected.
     *
     * [language] is the language [text] is written in, not a preference: it decides whether the
     * Indonesian number speller runs, and running that over English digits misstates them. See
     * [spokenForm].
     */
    fun speak(voiceId: String, text: String, language: Language): Flow<ByteArray>

    /**
     * Opens the connection this synthesizer will need, before a turn needs it.
     *
     * # Why this is worth a call of its own
     *
     * The first request to a host pays for DNS, the TCP handshake and the TLS negotiation, and on a
     * handset over mobile data that is a few hundred milliseconds. Paid inside a turn it lands in
     * the silence the customer is already sitting in; paid when the conversation opens it lands
     * while they are still reading the screen and the avatar session is being negotiated, which is
     * time being spent anyway.
     *
     * The shape of the evidence: the first answer of a conversation was consistently slower than
     * the ones after it — 1376ms to first token against 1044-1270ms — with nothing else different
     * between them.
     *
     * Best-effort by contract: a failure here must not fail anything, because the connection it
     * could not open will simply be opened by the turn that needs it. Defaults to doing nothing,
     * for implementations with no connection to warm.
     */
    suspend fun warm() = Unit
}

/**
 * [SpeechSynthesizer] over ElevenLabs' streaming endpoint.
 *
 * # Why streaming, and why PCM
 *
 * The synthesis sits in the middle of a turn: the face cannot move until samples arrive. Rendering
 * the whole line before sending any of it makes the customer wait for the *last* sample to hear the
 * first. `/stream` emits as it renders, so the first second of audio is on its way to the avatar
 * while the rest is still being generated.
 *
 * `pcm_24000` is not a preference. It is the one format LiveAvatar LITE accepts, so asking for mp3
 * here would mean decoding it back before it could be sent.
 *
 * Usage:
 * ```
 * synthesizer.speak(voiceId = agent.avatar.voiceId, text = answer)
 *     .collect { frame -> session.speak(frame) }
 * ```
 */
class ElevenLabsSynthesizer(
    private val calls: Call.Factory,
    private val apiKey: String,
    private val baseUrl: String = ELEVENLABS_BASE_URL,
    private val modelId: String = MODEL_FLASH_V2_5,
    private val json: Json = DefaultJson,
) : SpeechSynthesizer {

    override fun speak(voiceId: String, text: String, language: Language): Flow<ByteArray> = flow {
        require(text.isNotBlank()) { "refusing to synthesize an empty line" }

        val request = Request.Builder()
            .url("$baseUrl/v1/text-to-speech/$voiceId/stream?output_format=$OUTPUT_FORMAT")
            // The key rides a header, never the query string: a query string lands in access logs.
            .header("xi-api-key", apiKey)
            // The synthesizer is handed the *spoken* form; the transcript keeps the digits. See
            // [spokenForm] for why a bank's companion cannot read "Rp3.240.000" out as characters.
            .post(
                json.encodeToString(
                    SynthesisRequestDto(
                        text = spokenForm(text, language),
                        modelId = modelId,
                        languageCode = language.key,
                        applyTextNormalization = TEXT_NORMALIZATION_OFF,
                    ),
                ).toRequestBody(JsonMedia),
            )
            .build()

        calls.newCall(request).execute().use { response ->
            // ElevenLabs sends its status line before any audio, so a rejected key, an unknown voice
            // id or a rate limit fails here — before a billed avatar session has been opened.
            if (!response.isSuccessful) {
                throw SynthesisException(
                    "text-to-speech returned HTTP ${response.code}: " +
                        response.body.string().take(ERROR_SNIPPET_CHARS).trim(),
                )
            }

            val source = response.body.source()
            val pending = Buffer()
            var total = 0L
            // Small for the frame the customer waits on, full-sized for the rest. See [LEAD_FRAME_BYTES].
            var frame = LEAD_FRAME_BYTES
            while (true) {
                val read = source.read(pending, frame - pending.size)
                if (read == -1L) break
                total += read
                if (total > MAX_SYNTHESIS_BYTES) {
                    throw SynthesisException("text-to-speech produced more than $MAX_SYNTHESIS_BYTES bytes")
                }
                if (pending.size == frame) {
                    emit(pending.readByteArray())
                    frame = FRAME_BYTES
                }
            }
            if (pending.size > 0) emit(pending.readByteArray())
            if (total == 0L) throw SynthesisException("text-to-speech returned no audio")
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Opens a connection to the synthesis host by asking it something trivial.
     *
     * `/v1/models` is a listing this app never reads: it is the cheapest authenticated GET the
     * provider offers, it bills nothing, and what it returns is thrown away. The point is the
     * connection it leaves behind in OkHttp's pool, which the first synthesis of the conversation
     * then reuses instead of negotiating its own.
     */
    override suspend fun warm() {
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$baseUrl/v1/models")
                    .header("xi-api-key", apiKey)
                    .build()
                calls.newCall(request).execute().use { it.body.bytes() }
            }
        }
    }

    companion object {
        const val ELEVENLABS_BASE_URL = "https://api.elevenlabs.io"

        /**
         * The low-latency multilingual model. Synthesis is in-band here — the customer waits for it
         * between asking and seeing lips move — and Flash exists for exactly this position in a
         * pipeline. `eleven_multilingual_v2` renders marginally better and noticeably slower; it is
         * the knob to reach for if the demo sounds rushed rather than late.
         */
        const val MODEL_FLASH_V2_5 = "eleven_flash_v2_5"

        /** PCM signed 16-bit little-endian, 24 kHz, mono. Fixed by what LiveAvatar LITE accepts. */
        private const val OUTPUT_FORMAT = "pcm_24000"

        private const val SAMPLE_RATE_HZ = 24_000L
        private const val BYTES_PER_SAMPLE = 2L

        /**
         * One second of audio. LiveAvatar recommends ~1 s chunks and caps a packet at 1 MB; 48 kB
         * sits comfortably inside that even after base64 expands it by a third.
         */
        const val FRAME_BYTES = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE

        /**
         * The first frame, deliberately shorter: 200 ms of audio.
         *
         * Every frame but the first is sent while the avatar is already talking, so its size costs
         * nothing. The first one is the one the customer waits on in silence, and holding it back
         * until a whole second had rendered was waiting for audio the mouth would not reach for
         * another 800 ms. Measured against the real endpoint, on the request this client actually
         * sends: first byte at 225 ms, this lead frame out the door five ms later at 230 ms, against
         * 304 ms to fill a whole one-second frame — so the customer hears something ~75 ms sooner on
         * a desk connection, and more on a handset, where the gap is a transfer rather than a
         * render. Compare like with like if you re-measure: a full frame took 352 ms with the
         * provider's text normalization left on, and that is a different request.
         *
         * Not smaller than this. Each frame is a websocket command of its own, and a lead frame of
         * a few milliseconds would trade the wait for a burst of packets and a renderer that starts
         * with nothing to buffer — a starved mouth that stalls mid-word, which is the failure a
         * customer actually notices.
         */
        const val LEAD_FRAME_BYTES = SAMPLE_RATE_HZ * BYTES_PER_SAMPLE / 5

        /**
         * A ceiling on one synthesis, so a confused provider cannot exhaust the heap. An agent's
         * answer is a handful of sentences; three minutes of audio is already absurd.
         */
        private const val MAX_SYNTHESIS_BYTES = 8L shl 20

        private const val ERROR_SNIPPET_CHARS = 512

        /**
         * Written out rather than defaulted on the DTO: [DefaultJson] leaves `encodeDefaults` off,
         * so a field equal to its own default never reaches the wire and the provider quietly
         * applies its own.
         */
        private const val TEXT_NORMALIZATION_OFF = "off"

        private val JsonMedia = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
private data class SynthesisRequestDto(
    val text: String,
    @SerialName("model_id") val modelId: String,

    /**
     * The language [text] is in, so Flash does not have to work it out for itself.
     *
     * The turn already knows this — it is what chose the voice and ran the number speller — and
     * telling the synthesizer saves it a detection pass on every clause. It also removes the case
     * where a short clause with no function words ("Rp3.240.000.") is detected as the wrong
     * language and spoken with the wrong accent.
     */
    @SerialName("language_code") val languageCode: String,

    /**
     * ElevenLabs' own text normalization, off.
     *
     * [spokenForm] has already turned every figure in this line into the words it should be said
     * as, in the language it is being said in. Leaving the provider's normalizer on top of that is
     * a second opinion on Indonesian currency, taken after the decision was already made correctly,
     * and it costs latency to render. Measured: 222 ms to first byte against 259 ms with it on.
     */
    @SerialName("apply_text_normalization") val applyTextNormalization: String,
)
