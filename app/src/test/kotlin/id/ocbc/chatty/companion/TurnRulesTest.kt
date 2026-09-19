package id.ocbc.chatty.companion

import id.ocbc.chatty.core.ai.telemetry.TurnOutcome

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
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

/**
 * Whether a speculative answer may be used, which is the whole of what makes speculating safe.
 *
 * Getting this wrong in one direction wastes an API call. Getting it wrong in the other lets the
 * customer hear an answer to a question they had not finished asking, in a banking app. The tests
 * lean on that asymmetry: anything that changes a word must be treated as a different question.
 */
class SameQuestionTest {

    @Test
    fun `finalising tidies punctuation and case, and that is the same question`() {
        // Exactly what a recogniser does on the way out, and if this were not allowed the
        // speculation would essentially never be usable.
        assertTrue(TurnRules.sameQuestion("berapa saldo saya", "Berapa saldo saya?"))
        assertTrue(TurnRules.sameQuestion("how much do i have", "How much do I have?"))
    }

    @Test
    fun `extra whitespace is not a different question`() {
        assertTrue(TurnRules.sameQuestion("berapa  saldo   saya", "berapa saldo saya"))
    }

    @Test
    fun `a customer who kept talking asked something else`() {
        // The case this exists to catch: speculation fired on a pause, they carried on.
        assertFalse(TurnRules.sameQuestion("berapa saldo", "berapa saldo saya sekarang"))
    }

    @Test
    fun `a word the recogniser corrected is a different question`() {
        assertFalse(TurnRules.sameQuestion("berapa salju saya", "berapa saldo saya"))
    }

    @Test
    fun `dropping a word is a different question`() {
        assertFalse(TurnRules.sameQuestion("berapa saldo saya sekarang", "berapa saldo saya"))
    }

    @Test
    fun `a re-punctuated figure is the same question`() {
        // "Rp3.240.000" and "rp3 240 000" are the recogniser changing its mind about separators,
        // not the customer asking about a different number.
        assertTrue(TurnRules.sameQuestion("apakah Rp3.240.000 cukup", "Apakah rp3 240 000 cukup?"))
    }
}

/**
 * Telling the agent's own voice apart from the customer's.
 *
 * The failure this prevents is the worst-looking one in the app: the avatar answers, the microphone
 * hears the tail of it, and the agent starts talking to itself in front of whoever is watching.
 * The tests pull in both directions — an echo must be caught, and a customer must never be accused
 * of being one.
 */
class EchoTest {

    private val answer =
        "Total sekitar Rp400.800.000 tersebar di rekening gaji dan deposito. Mau saya rinci?"

    @Test
    fun `a verbatim run out of the answer is the room, not the customer`() {
        assertTrue(TurnRules.isEcho("tersebar di rekening gaji dan", answer))
    }

    @Test
    fun `the tail of the answer is what actually leaks`() {
        // The microphone opens near the end of the answer, so the end is what it catches.
        assertTrue(TurnRules.isEcho("rekening gaji dan deposito mau saya rinci", answer))
    }

    @Test
    fun `a customer asking a short question is not an echo`() {
        assertFalse(TurnRules.isEcho("berapa saldo saya", answer))
        assertFalse(TurnRules.isEcho("mau saya rinci", answer))
    }

    @Test
    fun `a customer reusing the agent's words in their own sentence is not an echo`() {
        // Genuine and common: they pick up a phrase and ask something with it. Four words of
        // overlap, below the run length, so it stands.
        assertFalse(TurnRules.isEcho("apa itu rekening gaji saya", answer))
    }

    @Test
    fun `with no previous answer nothing can be an echo`() {
        assertFalse(TurnRules.isEcho("tersebar di rekening gaji dan", null))
        assertFalse(TurnRules.isEcho("tersebar di rekening gaji dan", ""))
    }

    @Test
    fun `punctuation and case do not hide an echo`() {
        assertTrue(TurnRules.isEcho("TERSEBAR, DI REKENING GAJI DAN!", answer))
    }
}
