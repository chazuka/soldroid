package id.ocbc.chatty.core.ai

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The prompt has to be written the way it tells the model to write.
 *
 * # Why this is a test and not a note
 *
 * The prompt banned em dashes, en dashes and semicolons, and then used ten of the first and two of
 * the last in its own body. A model reads the instruction and the instruction's own prose at the
 * same time, so a rule stated in the style it forbids is a rule with a counter-example attached,
 * and the dashes kept coming back in answers after the ban shipped.
 *
 * Nothing about this is enforceable by reading the file once, because the prompt is edited often
 * and by hand. It is a test so that the next edit that reaches for a dash fails here instead of on
 * a handset.
 */
class AdvisorPromptStyleTest {

    private val prompt = advisorPrompt(
        displayName = "Alvin",
        tagline = "your relationship manager",
        customerRecordJson = "{}",
    )

    @Test
    fun `the prompt uses none of the punctuation it forbids`() {
        val forbidden = mapOf(
            '—' to "em dash",
            '–' to "en dash",
            ';' to "semicolon",
        )
        val used = forbidden.filterKeys { prompt.contains(it) }.values.toList()

        assertEquals(emptyList(), used, "the prompt demonstrates what it bans: $used")
    }

    @Test
    fun `every other instruction the model is sent obeys the rule too`() {
        // The ban is about what the model is shown, not about which file it came from, and the
        // per-turn directive is shown alongside the prompt on every question.
        for (language in Language.entries) {
            val directive = languageDirective(language)
            for (mark in listOf('\u2014', '\u2013', ';')) {
                assertTrue(!directive.contains(mark), "$language directive uses '$mark'")
            }
        }
    }

    @Test
    fun `the rule itself is still stated`() {
        // The cheap way to pass the test above is to delete the rule. It has to survive.
        assertTrue(prompt.contains("long dash"), "the ban has to be readable without being shown")
        assertTrue(prompt.contains("semicolon"))
    }

    @Test
    fun `the customer record is interpolated rather than described`() {
        // Guards the only structural thing the rewrite could plausibly have broken.
        assertTrue(advisorPrompt("Alvin", "", """{"name":"Budi"}""").contains(""""name":"Budi""""))
    }
}
