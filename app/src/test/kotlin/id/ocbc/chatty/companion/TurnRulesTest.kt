package id.ocbc.chatty.companion

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
