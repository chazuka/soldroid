package id.ocbc.chatty.core.ai

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Which language an answer comes back in.
 *
 * # The bug these exist for
 *
 * The switch on the top bar was never sent to the model at all. The standing prompt said "reply in
 * the language the customer used", which reads fine and fails from the second turn on: "the
 * customer" is a transcript by then, and the model follows whichever language dominates it.
 * Reported from a handset with the switch on EN, an English "good afternoon", and an Indonesian
 * answer, because every earlier line had been Indonesian.
 */
class LanguageDirectiveTest {

    @Test
    fun `the switch is what the model is told to answer in`() {
        assertTrue(languageDirective(Language.ENGLISH).contains("Answer in English"))
        assertTrue(languageDirective(Language.INDONESIAN).contains("Answer in Indonesian"))
    }

    @Test
    fun `the other language is named as the one exception`() {
        // The switch is a default, not a gag. A customer who switches to EN and then asks in
        // Indonesian still wants an Indonesian answer, and that was the behaviour before this
        // existed; it must survive the fix for the opposite case.
        val directive = languageDirective(Language.ENGLISH)

        assertTrue(directive.contains("clearly in Indonesian"))
        assertTrue(directive.contains("answer in Indonesian"))
    }

    @Test
    fun `the history is explicitly told not to decide`() {
        // The actual defect. Without this sentence the model averages over the transcript, which is
        // how one English greeting lost to five Indonesian turns before it.
        for (language in Language.entries) {
            assertTrue(
                languageDirective(language).contains("Earlier turns do not decide this"),
                "$language must not leave the choice to the transcript",
            )
        }
    }

    @Test
    fun `a language names itself the same way whichever side it is on`() {
        // Guards a copy-paste shape: both halves of the sentence are built from the same enum, so a
        // rename cannot leave the default and the exception disagreeing about what a language is
        // called.
        assertEquals("Indonesian", Language.INDONESIAN.spokenName)
        assertEquals("English", Language.ENGLISH.spokenName)
        assertEquals(Language.ENGLISH.spokenName, Language.INDONESIAN.toggled().spokenName)
    }

    @Test
    fun `the standing prompt no longer decides the language by itself`() {
        // The two rules would contradict each other: one says follow the customer's words, the
        // other says the switch is the default. The prompt keeps the formatting half and defers.
        val prompt = advisorPrompt(displayName = "Alvin", tagline = "", customerRecordJson = "{}")

        assertTrue(
            !prompt.contains("Reply in the language the customer used"),
            "the standing prompt must not carry a rule that arrives per turn",
        )
        assertTrue(prompt.contains("Rp3.240.000"), "how to write an amount is still its job")
    }
}
