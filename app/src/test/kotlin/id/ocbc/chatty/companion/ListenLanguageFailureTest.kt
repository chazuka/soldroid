package id.ocbc.chatty.companion

import id.ocbc.chatty.core.ai.Language
import kotlin.test.assertEquals
import org.junit.Test

/**
 * Re-pointing the ear when the recogniser hears speech and makes nothing of it.
 *
 * # The case this exists for
 *
 * [ListenLanguage.next] learns from the transcript, so it learns nothing from a question that never
 * became one — and that is exactly when the ear is most likely to be wrong. Measured on a handset
 * with the switch on EN and an Indonesian question spoken at it: six consecutive failures over
 * eighty seconds, every one reporting speech detected, every retry using the same configuration
 * that had just failed, and the customer getting silence with no way to tell why.
 */
class ListenLanguageFailureTest {

    @Test
    fun `one failure is not evidence`() {
        // A cough, a passing truck, a half-swallowed word. The ear must not move on any of them.
        val decision = ListenLanguage.afterFailure(Language.ENGLISH, failures = 1)

        assertEquals(Language.ENGLISH, decision.language)
        assertEquals(1, decision.streak, "the evidence is kept, not acted on")
    }

    @Test
    fun `two in a row moves the ear`() {
        // The alternative explanations do not repeat; a customer speaking the other language does.
        val decision = ListenLanguage.afterFailure(Language.ENGLISH, failures = 2)

        assertEquals(Language.INDONESIAN, decision.language)
    }

    @Test
    fun `the streak is spent when it is acted on`() {
        // Otherwise the very next failure flips it straight back, and the ear oscillates once per
        // utterance over a room that is simply noisy.
        assertEquals(0, ListenLanguage.afterFailure(Language.ENGLISH, failures = 2).streak)
    }

    @Test
    fun `it moves in both directions`() {
        assertEquals(
            Language.ENGLISH,
            ListenLanguage.afterFailure(Language.INDONESIAN, failures = 2).language,
        )
    }

    @Test
    fun `the same threshold governs both ways of learning`() {
        // Two questions in the other language move the ear, and so do two failures. A reader
        // changing one and not the other is the bug this pins.
        val byTranscript = ListenLanguage.next(Language.ENGLISH, Language.INDONESIAN, streak = 1)
        val byFailure = ListenLanguage.afterFailure(Language.ENGLISH, failures = 2)

        assertEquals(byTranscript.language, byFailure.language)
    }
}
