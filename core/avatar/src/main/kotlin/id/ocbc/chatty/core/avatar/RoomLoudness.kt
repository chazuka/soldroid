package id.ocbc.chatty.core.avatar

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decides whether the avatar is making a sound, from the audio itself.
 *
 * # Why not ask LiveKit
 *
 * LiveKit has an answer to this already, `RoomEvent.ActiveSpeakersChanged`, and it is the obvious
 * thing to use. It is also computed by the *server*: the SFU reads audio levels off the publisher's
 * RTP and forwards a speaker list, so a subscriber only learns who is talking if that deployment
 * sends it. LiveAvatar's does not. Measured over eleven consecutive turns on a handset, with the
 * avatar plainly audible throughout, the event fired zero times.
 *
 * That mattered more than a missing statistic. The app gates its microphone on this flag, so
 * handsfree's strongest defence against the agent interviewing itself had never once engaged; the
 * audio-length floor was carrying it alone. The measurement and the safety feature were the same
 * value, and both were dead.
 *
 * Decoded PCM, on the other hand, is already arriving on this handset for playback and cannot be
 * withheld by anyone. This class turns it into the same boolean, locally.
 *
 * # How it decides
 *
 * WebRTC delivers roughly ten milliseconds of samples at a time, continuously, and silence arrives
 * as buffers of near-zero samples rather than as no buffers at all. So each buffer is reduced to one
 * peak amplitude and compared against [FLOOR]; speech starts on the first loud buffer and stops only
 * after [QUIET_MS] of unbroken quiet, so the ordinary gaps between words do not flap the flag.
 *
 * Cheap on purpose: [feed] runs on WebRTC's audio thread, where blocking is a glitch in the answer
 * the customer is listening to. It touches no locks and allocates nothing. Every sample is *not*
 * examined — see [STRIDE].
 *
 * ```
 * val loudness = RoomLoudness()
 * track.addSink { data, bits, rate, channels, frames, _ ->
 *     if (loudness.feed(data, bits, rate, channels, frames)) onSpeakingChanged(loudness.speaking)
 * }
 * ```
 */
internal class RoomLoudness {

    /** Whether the room is carrying sound right now. Read from any thread. */
    @Volatile
    var speaking: Boolean = false
        private set

    /** Milliseconds of quiet seen since the last loud buffer. Only touched by [feed]. */
    private var quietMs = 0

    /**
     * Offers one buffer of audio and returns true when [speaking] changed as a result.
     *
     * Returning the transition rather than the state is what keeps the caller off the audio thread:
     * it can ignore the ninety-nine buffers a second that change nothing and only post the hundredth.
     *
     * A buffer that is not 16-bit is counted as quiet rather than guessed at. Every WebRTC pipeline
     * this app has seen delivers 16-bit, and inventing a reading for a format we cannot parse would
     * be worse than reporting silence, which merely opens the microphone a little early.
     */
    fun feed(
        audio: ByteBuffer,
        bitsPerSample: Int,
        sampleRate: Int,
        channels: Int,
        frames: Int,
    ): Boolean {
        val loud = bitsPerSample == BITS_PER_SAMPLE && peakOf(audio) >= FLOOR
        val bufferMs = if (sampleRate > 0) frames * MILLIS_PER_SECOND / sampleRate else 0
        if (channels <= 0) return false

        if (loud) {
            quietMs = 0
            return flip(to = true)
        }
        quietMs += bufferMs
        return if (quietMs >= QUIET_MS) flip(to = false) else false
    }

    /** Forgets what it heard. Called when the track goes away, so a new one starts from silence. */
    fun reset() {
        quietMs = QUIET_MS
        speaking = false
    }

    private fun flip(to: Boolean): Boolean {
        if (speaking == to) return false
        speaking = to
        return true
    }

    /**
     * The loudest sample in the buffer, as an absolute 16-bit amplitude.
     *
     * Reads the buffer's own byte order rather than assuming one, and does not disturb the caller's
     * position, because the same buffer is on its way to the speaker.
     */
    private fun peakOf(audio: ByteBuffer): Int {
        val order = audio.order()
        val shorts = audio.duplicate().order(order).asShortBuffer()
        var peak = 0
        var i = 0
        while (i < shorts.limit()) {
            val sample = kotlin.math.abs(shorts.get(i).toInt())
            if (sample > peak) peak = sample
            i += STRIDE
        }
        audio.order(order)
        return peak
    }

    private companion object {
        const val BITS_PER_SAMPLE = 16
        const val MILLIS_PER_SECOND = 1_000

        /**
         * The amplitude above which the room counts as carrying speech, out of a 16-bit full scale
         * of 32,767. About -36 dBFS.
         *
         * Set to clear the noise floor of an encoded-then-decoded stream, which is not digital
         * silence even when nobody is talking, while still catching the quiet end of a spoken
         * sentence. Erring low is the safe direction: this flag keeps the microphone *shut*, so a
         * threshold that is too sensitive costs a slightly later re-open, and one that is too deaf
         * costs the agent hearing itself.
         */
        const val FLOOR = 500

        /**
         * How long the room must stay quiet before the avatar is called finished.
         *
         * Long enough to ride over the pause between two sentences, which would otherwise read as
         * the answer having ended and re-open the microphone into the middle of it. Short enough
         * that the customer is not left waiting after a real ending.
         */
        const val QUIET_MS = 400

        /**
         * Examine every eighth sample.
         *
         * This runs on the audio thread a hundred times a second, and a peak is not a statistic that
         * needs every sample: speech at a level this cares about lasts tens of milliseconds, so it
         * cannot hide between two samples 0.17ms apart at 48 kHz.
         */
        const val STRIDE = 8
    }
}
