package id.ocbc.chatty.companion

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

/**
 * The handsfree state machine, covered at the two places it has actually failed.
 *
 * Both bugs shipped, both were invisible on the happy path, and both had the same symptom: the mode
 * left switched on and deaf, the button claiming to listen while nothing was. The regression tests
 * are therefore about failure handling, not about the case where speech is heard.
 */
class HandsfreeTest {

    // --- what to do about a failed listen -------------------------------------------------------

    @Test
    fun `a silent room is retried and never mentioned`() {
        val recovery = Handsfree.recover(SpeechProblem.NO_MATCH, on = true)
        assertNull(recovery.notify, "silence is why the microphone is open; saying so is noise")
        assertEquals(true, recovery.rearm)
        assertEquals(0L, recovery.backoffMs, "a real question should not wait behind a backoff")
    }

    @Test
    fun `an outage keeps the loop alive`() {
        // The first shipped bug. NO_NETWORK stopped looking like a silent room, so the loop stopped
        // re-arming and handsfree sat there switched on and deaf until it was toggled by hand.
        val recovery = Handsfree.recover(SpeechProblem.NO_NETWORK, on = true)
        assertEquals(true, recovery.rearm, "handsfree must survive an outage and recover by itself")
        assertEquals(SpeechProblem.NO_NETWORK, recovery.notify)
        assertEquals(Handsfree.FAULT_BACKOFF_MS, recovery.backoffMs)
    }

    @Test
    fun `a recogniser fault keeps the loop alive`() {
        // The second shipped bug, from the same mistake: the retryable list was named explicitly and
        // FAILED was not on it. An outage produced NO_NETWORK twice and then FAILED, and the loop
        // died on the third.
        val recovery = Handsfree.recover(SpeechProblem.FAILED, on = true)
        assertEquals(true, recovery.rearm)
        assertEquals(Handsfree.FAULT_BACKOFF_MS, recovery.backoffMs, "do not hammer a sick recogniser")
    }

    @Test
    fun `only what a wait cannot fix ends the loop`() {
        for (problem in listOf(
            SpeechProblem.PERMISSION_DENIED,
            SpeechProblem.PERMISSION_JUST_GRANTED,
            SpeechProblem.UNAVAILABLE,
        )) {
            val recovery = Handsfree.recover(problem, on = true)
            assertEquals(false, recovery.rearm, "$problem cannot be fixed by trying again")
            assertEquals(problem, recovery.notify, "$problem has to reach the customer")
        }
    }

    @Test
    fun `with handsfree off every problem is simply reported`() {
        val recovery = Handsfree.recover(SpeechProblem.NO_MATCH, on = false)
        assertEquals(SpeechProblem.NO_MATCH, recovery.notify)
        assertEquals(false, recovery.rearm)
    }

    // --- what the failure really was -----------------------------------------------------------

    @Test
    fun `no network is not the customer mumbling`() {
        // Offline, the recogniser reports the same code it uses for a silent room, so the copy
        // blamed their speech for a dead connection.
        assertEquals(
            SpeechProblem.NO_NETWORK,
            Handsfree.classify(SpeechProblem.NO_MATCH, hasNetwork = false),
        )
        assertEquals(
            SpeechProblem.NO_MATCH,
            Handsfree.classify(SpeechProblem.NO_MATCH, hasNetwork = true),
        )
    }

    @Test
    fun `a network check never rewrites a different failure`() {
        assertEquals(
            SpeechProblem.PERMISSION_DENIED,
            Handsfree.classify(SpeechProblem.PERMISSION_DENIED, hasNetwork = false),
        )
    }

    // --- when the microphone should be open ----------------------------------------------------

    @Test
    fun `the microphone is shut for the whole of a turn`() {
        // The agent speaks through the same handset the microphone is in.
        assertEquals(
            Handsfree.Intent.Close,
            Handsfree.intent(on = true, onStage = true, accepting = false, avatarSpeaking = false, reconnecting = false, quietMs = 0, retryDelayMs = 0),
        )
    }

    @Test
    fun `the thread gets no microphone`() {
        assertEquals(
            Handsfree.Intent.Close,
            Handsfree.intent(on = true, onStage = false, accepting = true, avatarSpeaking = false, reconnecting = false, quietMs = 0, retryDelayMs = 0),
        )
    }

    @Test
    fun `an armed and idle stage opens the microphone`() {
        assertEquals(
            Handsfree.Intent.Listen(Handsfree.REARM_MS),
            Handsfree.intent(on = true, onStage = true, accepting = true, avatarSpeaking = false, reconnecting = false, quietMs = 0, retryDelayMs = 0),
        )
    }

    @Test
    fun `a backoff delays the next listen without stopping it`() {
        assertEquals(
            Handsfree.Intent.Listen(Handsfree.REARM_MS + Handsfree.FAULT_BACKOFF_MS),
            Handsfree.intent(
                on = true,
                onStage = true,
                accepting = true,
                avatarSpeaking = false,
                reconnecting = false,
                quietMs = 0,
                retryDelayMs = Handsfree.FAULT_BACKOFF_MS,
            ),
        )
    }

    @Test
    fun `a long enough silence ends the mode`() {
        assertEquals(
            Handsfree.Intent.GiveUp,
            Handsfree.intent(
                on = true,
                onStage = true,
                accepting = true,
                avatarSpeaking = false,
                reconnecting = false,
                quietMs = Handsfree.QUIET_MS + 1,
                retryDelayMs = 0,
            ),
        )
    }

    @Test
    fun `a pause for thought does not end the mode`() {
        // Forty-five seconds used to, which killed the one scenario handsfree exists for: the phone
        // face-down on a desk while the customer considers the answer.
        assertEquals(
            Handsfree.Intent.Listen(Handsfree.REARM_MS),
            Handsfree.intent(on = true, onStage = true, accepting = true, avatarSpeaking = false, reconnecting = false, quietMs = 60_000, retryDelayMs = 0),
        )
    }

    // --- what letting go of the button means ---------------------------------------------------

    @Test
    fun `a press too short to be speech is discarded`() {
        assertEquals(Handsfree.Release.DISCARD, Handsfree.release(on = false, heldMs = 120))
    }

    @Test
    fun `a real hold is submitted`() {
        assertEquals(Handsfree.Release.SUBMIT, Handsfree.release(on = false, heldMs = 1_500))
    }

    @Test
    fun `handsfree ends its own listens`() {
        assertEquals(Handsfree.Release.IGNORE, Handsfree.release(on = true, heldMs = 1_500))
    }

    @Test
    fun `the microphone stays shut while the avatar is audible`() {
        // The turn can be over and the answer still coming out of the speaker: the provider reports
        // the end of an utterance, and the audio is still in a jitter buffer on its way out. Opened
        // then, the microphone hears the rest of the answer and the agent interviews itself.
        assertEquals(
            Handsfree.Intent.Close,
            Handsfree.intent(
                on = true,
                onStage = true,
                accepting = true,
                avatarSpeaking = true,
                reconnecting = false,
                quietMs = 0,
                retryDelayMs = 0,
            ),
        )
    }

    @Test
    fun `it opens once the avatar has gone quiet`() {
        assertEquals(
            Handsfree.Intent.Listen(Handsfree.REARM_MS),
            Handsfree.intent(
                on = true,
                onStage = true,
                accepting = true,
                avatarSpeaking = false,
                reconnecting = false,
                quietMs = 0,
                retryDelayMs = 0,
            ),
        )
    }

    @Test
    fun `the microphone stays shut while the room is reconnecting`() {
        // Nothing said into a room that has dropped reaches anyone, and the face on screen is a
        // still frame. Listening would only collect a question for an avatar that cannot answer it.
        assertEquals(
            Handsfree.Intent.Close,
            Handsfree.intent(
                on = true,
                onStage = true,
                accepting = true,
                avatarSpeaking = false,
                reconnecting = true,
                quietMs = 0,
                retryDelayMs = 0,
            ),
        )
    }
}
