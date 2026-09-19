package id.ocbc.chatty.companion

import id.ocbc.chatty.core.ai.telemetry.TurnOutcome

import kotlin.test.assertEquals
import org.junit.Test

/** The end of a turn, covered at the three places it has shipped a bug. */
class TurnRulesTest {

    @Test
    fun `a turn that spoke lets the caption finish`() {
        // The reveal is behind the voice by design; cutting it off left answers half-shown.
        val ending = TurnRules.ending(answered = true, spoke = true, interrupted = false, hadSession = true)
        assertEquals(TurnRules.Captions.DRAIN, ending.captions)
        assertEquals(false, ending.retryable)
        assertEquals(false, ending.replaceSession)
    }

    @Test
    fun `barge-in stops the caption where the voice stopped`() {
        val ending = TurnRules.ending(answered = true, spoke = true, interrupted = true, hadSession = true)
        assertEquals(TurnRules.Captions.FREEZE, ending.captions)
        assertEquals(false, ending.retryable, "they stopped it on purpose; nothing failed")
    }

    @Test
    fun `an answer that arrived is never offered again`() {
        // Speech failed but the text is already in the thread. A retry here would ask the customer
        // to pay for the same answer twice.
        val ending = TurnRules.ending(answered = true, spoke = false, interrupted = false, hadSession = true)
        assertEquals(false, ending.retryable)
        assertEquals(TurnRules.Captions.SHOW_ALL, ending.captions, "the rest will never be spoken")
    }

    @Test
    fun `a turn that produced nothing can be tried again`() {
        val ending = TurnRules.ending(answered = false, spoke = false, interrupted = false, hadSession = true)
        assertEquals(true, ending.retryable)
    }

    @Test
    fun `a failed speak spends the session`() {
        val ending = TurnRules.ending(answered = false, spoke = false, interrupted = false, hadSession = true)
        assertEquals(true, ending.replaceSession, "a speak that failed took the socket with it")
    }

    @Test
    fun `with no session there is nothing to replace`() {
        val ending = TurnRules.ending(answered = true, spoke = false, interrupted = false, hadSession = false)
        assertEquals(false, ending.replaceSession)
        assertEquals(TurnRules.Captions.SHOW_ALL, ending.captions, "captions-only still has to show the answer")
    }

    @Test
    fun `provider events outside a turn do not move the phase`() {
        // This is the one that could hang the screen: a stray speak event left the pill on
        // "Berbicara", which disables the microphone, and nothing ever cleared it.
        assertEquals(false, TurnRules.phaseMayMove(turnInFlight = false))
        assertEquals(true, TurnRules.phaseMayMove(turnInFlight = true))
    }
}

/**
 * How a finished turn is reported to whoever collects turns.
 *
 * Worth its own tests because every case here is silent when it is wrong: nothing breaks, the
 * customer sees nothing, and the only symptom is a dashboard that quietly overstates or understates
 * how often this pipeline fails — which is the number the whole point of collecting turns.
 */
class TurnOutcomeTest {

    @Test
    fun `the turn everyone is hoping for`() {
        assertEquals(
            TurnOutcome.SPOKEN,
            TurnRules.outcome(answered = true, spoke = true, interrupted = false, network = false),
        )
    }

    @Test
    fun `an answer nobody said out loud is still an answer`() {
        // Synthesis or the avatar failed. The customer has their answer in the thread, so this is
        // not a failed turn — but it is not a working face either, and the two need telling apart.
        assertEquals(
            TurnOutcome.ANSWERED_IN_TEXT,
            TurnRules.outcome(answered = true, spoke = false, interrupted = false, network = false),
        )
    }

    @Test
    fun `barge-in is the feature working, not a fault`() {
        assertEquals(
            TurnOutcome.INTERRUPTED,
            TurnRules.outcome(answered = true, spoke = true, interrupted = true, network = false),
        )
    }

    @Test
    fun `an interrupted turn stays interrupted however it unwound`() {
        // Cutting the avatar off can take the rest of the turn down with it. Counting that as a
        // network fault would report an outage every time somebody pressed stop.
        assertEquals(
            TurnOutcome.INTERRUPTED,
            TurnRules.outcome(answered = false, spoke = false, interrupted = true, network = true),
        )
    }

    @Test
    fun `a dropped connection is named as one`() {
        assertEquals(
            TurnOutcome.FAILED_NETWORK,
            TurnRules.outcome(answered = false, spoke = false, interrupted = false, network = true),
        )
    }

    @Test
    fun `a vendor refusing is not the customer's connection`() {
        assertEquals(
            TurnOutcome.FAILED_API,
            TurnRules.outcome(answered = false, spoke = false, interrupted = false, network = false),
        )
    }
}
