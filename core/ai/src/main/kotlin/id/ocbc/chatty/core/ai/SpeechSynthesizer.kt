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
                json.encodeToString(SynthesisRequestDto(spokenForm(text, language), modelId))
                    .toRequestBody(JsonMedia),
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
            while (true) {
                val read = source.read(pending, FRAME_BYTES - pending.size)
                if (read == -1L) break
                total += read
                if (total > MAX_SYNTHESIS_BYTES) {
                    throw SynthesisException("text-to-speech produced more than $MAX_SYNTHESIS_BYTES bytes")
                }
                if (pending.size == FRAME_BYTES) emit(pending.readByteArray())
            }
            if (pending.size > 0) emit(pending.readByteArray())
            if (total == 0L) throw SynthesisException("text-to-speech returned no audio")
        }
    }.flowOn(Dispatchers.IO)

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
         * A ceiling on one synthesis, so a confused provider cannot exhaust the heap. An agent's
         * answer is a handful of sentences; three minutes of audio is already absurd.
         */
        private const val MAX_SYNTHESIS_BYTES = 8L shl 20

        private const val ERROR_SNIPPET_CHARS = 512

        private val JsonMedia = "application/json; charset=utf-8".toMediaType()
    }
}

@Serializable
private data class SynthesisRequestDto(
    val text: String,
    @SerialName("model_id") val modelId: String,
)
