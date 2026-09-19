package id.ocbc.chatty.core.avatar

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Hearing the avatar from its own audio.
 *
 * # What these are really protecting
 *
 * Not a statistic. This flag is what keeps the microphone shut while the agent is talking, and the
 * signal it replaces — LiveKit's server-computed active speakers — fired zero times across eleven
 * measured turns on a handset, which meant that defence had never once engaged. A test that the
 * flag rises and falls on real-shaped audio is the only thing standing between here and repeating
 * that silently.
 */
class RoomLoudnessTest {

    /** One WebRTC delivery: 10ms of mono 48 kHz PCM, every sample at [amplitude]. */
    private fun buffer(amplitude: Int, frames: Int = 480): ByteBuffer =
        ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(frames) { putShort(amplitude.toShort()) }
            rewind()
        }

    private fun RoomLoudness.feed10ms(amplitude: Int) =
        feed(buffer(amplitude), bitsPerSample = 16, sampleRate = 48_000, channels = 1, frames = 480)

    @Test
    fun `silence is not speech`() {
        val loudness = RoomLoudness()

        assertFalse(loudness.feed10ms(0), "nothing changed, so nothing should be reported")
        assertFalse(loudness.speaking)
    }

    @Test
    fun `the first loud buffer is the avatar starting to talk`() {
        val loudness = RoomLoudness()

        assertTrue(loudness.feed10ms(8_000), "the transition has to be reported, not just stored")
        assertTrue(loudness.speaking)
    }

    @Test
    fun `a transition is reported once, not on every buffer that follows`() {
        // The caller posts to a StateFlow on this signal, from WebRTC's audio thread. Reporting
        // every buffer would be a hundred posts a second for a fact that changed twice a turn.
        val loudness = RoomLoudness()
        loudness.feed10ms(8_000)

        repeat(50) { assertFalse(loudness.feed10ms(8_000), "still speaking is not a change") }
    }

    @Test
    fun `a pause between words does not end the answer`() {
        // The failure this exists to prevent: the microphone re-opening into the gap between two
        // sentences, hearing the third, and handing the agent its own words as a question.
        val loudness = RoomLoudness()
        loudness.feed10ms(8_000)

        repeat(30) { loudness.feed10ms(0) }   // 300ms, under the hangover

        assertTrue(loudness.speaking, "a 300ms gap is a breath, not an ending")
    }

    @Test
    fun `a long enough quiet does end it`() {
        val loudness = RoomLoudness()
        loudness.feed10ms(8_000)

        val changed = (1..45).map { loudness.feed10ms(0) }

        assertFalse(loudness.speaking)
        assertEquals(1, changed.count { it }, "the ending is reported exactly once")
    }

    @Test
    fun `the noise floor of a decoded stream is not mistaken for speech`() {
        // An encoded-then-decoded silence is not digital silence, and treating its dither as talking
        // would hold the microphone shut for the whole conversation.
        val loudness = RoomLoudness()

        repeat(100) { loudness.feed10ms(60) }

        assertFalse(loudness.speaking)
    }

    @Test
    fun `quiet speech is still speech`() {
        val loudness = RoomLoudness()

        assertTrue(loudness.feed10ms(1_500), "the tail of a sentence is well under full scale")
    }

    @Test
    fun `a format this cannot read reports quiet rather than a guess`() {
        // Opening the microphone early is recoverable. Inventing an amplitude from bytes we cannot
        // parse is not, and it would be the kind of number that reads as authoritative and is not.
        val loudness = RoomLoudness()

        val changed = loudness.feed(buffer(20_000), bitsPerSample = 8, sampleRate = 48_000, channels = 1, frames = 480)

        assertFalse(changed)
        assertFalse(loudness.speaking)
    }

    @Test
    fun `a new track starts from silence`() {
        // A reconnect resubscribes, and whatever the last track was doing when it went away must
        // not be inherited: the flag would hold the microphone shut over a room carrying nothing.
        val loudness = RoomLoudness()
        loudness.feed10ms(8_000)

        loudness.reset()

        assertFalse(loudness.speaking)
        // And the hangover starts spent, so the first quiet buffer of a new track reports nothing.
        assertFalse(loudness.feed10ms(0))
    }

    @Test
    fun `a peak is found wherever it sits in the buffer`() {
        // Every eighth sample is examined. A loud passage lasts milliseconds, so it cannot hide
        // between two samples, but a single spike deliberately placed off-stride proves the stride
        // is the only thing being traded away.
        val loudness = RoomLoudness()
        val quietWithOneSpike = ByteBuffer.allocate(480 * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            repeat(480) { putShort(0) }
            putShort(16 * 2, 9_000)
            rewind()
        }

        assertTrue(
            loudness.feed(quietWithOneSpike, 16, 48_000, 1, 480),
            "a spike on a sampled index has to register",
        )
    }
}
