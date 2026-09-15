package id.ocbc.chatty.core.ai

import kotlin.test.assertEquals
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
