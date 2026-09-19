package id.ocbc.chatty.core.ai

import kotlin.reflect.full.memberProperties
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * That every number a turn measures actually reaches whoever is collecting turns.
 *
 * # The gap this closes
 *
 * The telemetry adapter used to name each field itself. Three marks were added to [TurnTrace] —
 * the frame reaching the socket, the sound reaching the room, and the provider's error about its
 * own timing — and not one of them was forwarded, because adding a field to a data class does not
 * make anything send it. Nothing failed, nothing warned, and the dashboard carried on charting an
 * avatar leg that had by then been shown to be measuring the wrong thing.
 *
 * The mapping moved into the trace so an adapter can only iterate it. This test is the other half:
 * it walks the properties by reflection, so a mark added tomorrow and forgotten fails here rather
 * than going quietly missing for weeks.
 */
class TurnMeasurementsTest {

    /** Every mark and leg populated, so nothing can be absent merely for being null. */
    private val full = TurnTrace(
        askedAtMs = 0,
        listenedMs = 120,
        firstTokenMs = 1_000,
        answerCompleteMs = 2_400,
        firstClauseMs = 1_300,
        firstAudioMs = 1_700,
        frameSentMs = 1_701,
        speakStartedMs = 2_600,
        audibleMs = 2_774,
        speakEndedMs = 12_000,
        sentences = 3,
        starved = 1,
        reconnects = 2,
    )

    @Test
    fun `every millisecond mark on the trace is reported`() {
        val reported = full.measurements()
        val missing = TurnTrace::class.memberProperties
            .filter { it.name.endsWith("Ms") && it.name != "askedAtMs" }
            .filter { it.get(full) != null }
            .map { it.name }
            .filterNot { name ->
                // measurements() names things the way a backend should group them, not the way
                // Kotlin spells them, so the match is on the value rather than on the spelling.
                val value = TurnTrace::class.memberProperties.first { it.name == name }.get(full)
                reported.containsValue((value as Number).toLong())
            }

        assertEquals(emptyList(), missing, "these marks never reach the backend: $missing")
    }

    @Test
    fun `the counts are reported even when they are zero`() {
        // A turn that never starved is evidence; an absent starved count is not. Unlike the marks,
        // zero is a real reading here and has to be sent.
        val quiet = TurnTrace(askedAtMs = 0).measurements()

        assertEquals(0L, quiet["starved"])
        assertEquals(0L, quiet["reconnects"])
        assertEquals(0L, quiet["sentences"])
    }

    @Test
    fun `a mark that never happened is absent rather than zero`() {
        // The rule the whole trace is built on. Zero would put a turn that failed before the avatar
        // spoke into the same bucket as one that was instant.
        val failed = TurnTrace(askedAtMs = 0, firstTokenMs = 900).measurements()

        assertTrue("audible_ms" !in failed)
        assertTrue("avatar_ms" !in failed)
        assertEquals(900L, failed["first_token_ms"])
    }

    @Test
    fun `the provider's error is reported signed`() {
        // It is the one value here that is meaningful when negative, and negative is its healthy
        // shape. A map that dropped it, or clamped it, would hide the finding it exists for.
        assertEquals(-174L, full.measurements()["claim_skew_ms"])
    }

    @Test
    fun `the avatar leg is reported from the sound, not the claim`() {
        // 2774 - 1700, not 2600 - 1700. Anchored on the claim this had four times the variance and
        // appeared to have a fast mode and a slow mode that do not exist.
        assertEquals(1_074L, full.measurements()["avatar_ms"])
    }
}
