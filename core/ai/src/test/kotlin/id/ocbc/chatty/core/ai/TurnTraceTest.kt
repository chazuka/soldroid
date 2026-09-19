package id.ocbc.chatty.core.ai

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class TurnTraceTest {

    @Test
    fun `a fresh trace is incomplete and renders every mark as unknown`() {
        val trace = TurnTrace(askedAtMs = 0L)

        assertFalse(trace.complete)
        assertEquals("llm —/—  tts —  lips —/—  0 sentence(s)", trace.summary())
    }

    @Test
    fun `a finished turn renders the marks it has`() {
        val trace = TurnTrace(
            askedAtMs = 0L,
            firstTokenMs = 3782,
            answerCompleteMs = 4691,
            firstAudioMs = 4365,
            speakStartedMs = 5425,
            speakEndedMs = 18679,
            sentences = 2,
        )

        assertTrue(trace.complete)
        assertEquals(
            "llm 3782ms/4691ms  tts 4365ms  lips 5425ms/18679ms  2 sentence(s)",
            trace.summary(),
        )
    }

    @Test
    fun `a trace is complete only once the avatar has stopped`() {
        val speaking = TurnTrace(askedAtMs = 0L, firstTokenMs = 100, speakStartedMs = 900)

        assertFalse(speaking.complete)
        assertTrue(speaking.copy(speakEndedMs = 4_000).complete)
    }

    @Test
    fun `an audio mark earlier than the answer mark is the overlap working, not a bug`() {
        // The pipeline synthesizes sentence one while the model is still writing sentence two, so
        // first-audio legitimately precedes answer-complete. This pins that expectation down.
        val trace = TurnTrace(askedAtMs = 0L, answerCompleteMs = 4691, firstAudioMs = 4365)

        assertTrue(trace.firstAudioMs!! < trace.answerCompleteMs!!)
    }
}

/**
 * The legs derived from the marks, which is how a chart answers "synthesizer or renderer".
 *
 * Each case here is one where returning zero would be worse than returning nothing: a turn that
 * never got audio is not a turn with instant synthesis, and putting the two in one bucket is how an
 * average starts flattering a pipeline that failed.
 */
class TurnLegsTest {

    @Test
    fun `synthesis is the gap between the first token and the first audio`() {
        val trace = TurnTrace(askedAtMs = 0, firstTokenMs = 1_000, firstAudioMs = 1_700)

        assertEquals(700L, trace.ttsMs)
    }

    @Test
    fun `the avatar is the gap between the first audio and the lips moving`() {
        val trace = TurnTrace(askedAtMs = 0, firstAudioMs = 1_700, speakStartedMs = 2_500)

        assertEquals(800L, trace.avatarMs)
    }

    @Test
    fun `a leg that never happened is absent, not zero`() {
        // A turn that failed before any audio. Zero would read as instant synthesis and drag every
        // average it lands in towards a number nothing achieved.
        val failed = TurnTrace(askedAtMs = 0, firstTokenMs = 1_000)

        assertNull(failed.ttsMs)
        assertNull(failed.avatarMs)
    }

    @Test
    fun `a leg with no beginning is absent too`() {
        // firstAudioMs without firstTokenMs should not happen, but a trace is assembled from events
        // that arrive out of a live pipeline, and a negative duration on a chart is worse than a gap.
        val odd = TurnTrace(askedAtMs = 0, firstAudioMs = 1_700)

        assertNull(odd.ttsMs)
    }

    @Test
    fun `marks that arrive out of order do not produce a negative leg`() {
        val backwards = TurnTrace(askedAtMs = 0, firstTokenMs = 2_000, firstAudioMs = 1_000)

        assertNull(backwards.ttsMs)
    }

    @Test
    fun `legs that are genuinely instant are still reported`() {
        val instant = TurnTrace(askedAtMs = 0, firstTokenMs = 1_000, firstAudioMs = 1_000)

        assertEquals(0L, instant.ttsMs)
    }
}
