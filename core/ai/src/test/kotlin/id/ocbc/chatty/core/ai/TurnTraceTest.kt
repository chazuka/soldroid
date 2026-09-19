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
        assertEquals(
            "llm —/—  tts —  wire —  lips —/—  audible —  0 sentence(s)",
            trace.summary(),
        )
    }

    @Test
    fun `a finished turn renders the marks it has`() {
        val trace = TurnTrace(
            askedAtMs = 0L,
            firstTokenMs = 3782,
            answerCompleteMs = 4691,
            firstAudioMs = 4365,
            frameSentMs = 4371,
            speakStartedMs = 5425,
            audibleMs = 5610,
            speakEndedMs = 18679,
            sentences = 2,
        )

        assertTrue(trace.complete)
        assertEquals(
            "llm 3782ms/4691ms  tts 4365ms  wire 4371ms  " +
                "lips 5425ms/18679ms  audible 5610ms  2 sentence(s)",
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
    fun `synthesis is timed from the clause, not from the first token`() {
        // The distinction that makes a voice comparison mean anything. Here the model spent 1.2s
        // finishing its opening sentence and the voice spent 300ms on it; charging the voice for
        // all 1.5s is how a wordy model came to look like a slow voice.
        val trace = TurnTrace(
            askedAtMs = 0,
            firstTokenMs = 1_000,
            firstClauseMs = 2_200,
            firstAudioMs = 2_500,
        )

        assertEquals(1_200L, trace.clauseMs)
        assertEquals(300L, trace.ttsMs)
    }

    @Test
    fun `a turn whose first clause never completed has no synthesis leg`() {
        // The model started writing and the turn died before a sentence was whole. There is nothing
        // to say about the voice, and saying nothing is the correct answer.
        val trace = TurnTrace(askedAtMs = 0, firstTokenMs = 1_000, firstAudioMs = 1_700)

        assertNull(trace.ttsMs)
        assertNull(trace.clauseMs)
    }

    @Test
    fun `the avatar is the gap between the first audio and the lips moving`() {
        val trace = TurnTrace(askedAtMs = 0, firstAudioMs = 1_700, speakStartedMs = 2_500)

        assertEquals(800L, trace.avatarMs)
    }

    // --- the avatar leg, split ------------------------------------------------------------------

    @Test
    fun `the avatar leg splits into what we cost and what the provider cost`() {
        // The whole point of the split: 1.3s of avatar could be either side of the wire, and one
        // opaque block cannot say which. Encoding is this app's to fix; rendering is not.
        val trace = TurnTrace(
            askedAtMs = 0,
            firstAudioMs = 1_700,
            frameSentMs = 1_712,
            speakStartedMs = 3_000,
        )

        assertEquals(1_300L, trace.avatarMs, "the leg as a whole is unchanged")
        assertEquals(12L, trace.uplinkMs, "base64 and JSON, which is ours")
        assertEquals(1_288L, trace.renderMs, "the provider's, which is not")
    }

    @Test
    fun `the room is what checks the provider's word`() {
        val trace = TurnTrace(askedAtMs = 0, speakStartedMs = 3_000, audibleMs = 3_185)

        assertEquals(185L, trace.roomMs)
    }

    @Test
    fun `a provider that claims lips after the room already had sound reports no room leg`() {
        // Observed on this app in the other direction: speak_ended arrived before the lips moved at
        // all. A control socket that describes a different utterance from the one being heard must
        // leave a gap here, not a negative number that averages away.
        val lying = TurnTrace(askedAtMs = 0, speakStartedMs = 3_400, audibleMs = 3_000)

        assertNull(lying.roomMs)
    }

    @Test
    fun `a build with no avatar has no sub-legs to report`() {
        val captionsOnly = TurnTrace(askedAtMs = 0, firstTokenMs = 900, firstAudioMs = 1_700)

        assertNull(captionsOnly.uplinkMs)
        assertNull(captionsOnly.renderMs)
        assertNull(captionsOnly.roomMs)
    }

    @Test
    fun `a leg that never happened is absent, not zero`() {
        // A turn that failed before any audio. Zero would read as instant synthesis and drag every
        // average it lands in towards a number nothing achieved.
        val failed = TurnTrace(askedAtMs = 0, firstTokenMs = 1_000, firstClauseMs = 1_400)

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
        val backwards = TurnTrace(askedAtMs = 0, firstClauseMs = 2_000, firstAudioMs = 1_000)

        assertNull(backwards.ttsMs)
    }

    @Test
    fun `legs that are genuinely instant are still reported`() {
        val instant = TurnTrace(askedAtMs = 0, firstClauseMs = 1_000, firstAudioMs = 1_000)

        assertEquals(0L, instant.ttsMs)
    }
}
