package id.ocbc.chatty.companion

import id.ocbc.chatty.core.ai.Language
import kotlin.test.assertEquals
import org.junit.Test

/** The rule that keeps one borrowed sentence from deafening the recogniser to the next one. */
class ListenLanguageTest {

    @Test
    fun `one question in the other language does not move the ear`() {
        // The case this rule exists for: an Indonesian conversation with one English sentence in it.
        // Re-pointing the recogniser here would mangle the Indonesian that follows.
        val first = ListenLanguage.next(Language.INDONESIAN, Language.ENGLISH, streak = 0)
        assertEquals(Language.INDONESIAN, first.language)
        assertEquals(1, first.streak, "the evidence is kept, it is just not acted on yet")
    }

    @Test
    fun `two in a row is a customer who has switched`() {
        val first = ListenLanguage.next(Language.INDONESIAN, Language.ENGLISH, streak = 0)
        val second = ListenLanguage.next(Language.INDONESIAN, Language.ENGLISH, first.streak)
        assertEquals(Language.ENGLISH, second.language)
        assertEquals(0, second.streak, "spent, so the next stray sentence starts from nothing")
    }

    @Test
    fun `going back resets the evidence`() {
        // English, then Indonesian again. The English question must not be left on the books to be
        // completed by an English question five turns later.
        val strayed = ListenLanguage.next(Language.INDONESIAN, Language.ENGLISH, streak = 0)
        val returned = ListenLanguage.next(Language.INDONESIAN, Language.INDONESIAN, strayed.streak)
        assertEquals(Language.INDONESIAN, returned.language)
        assertEquals(0, returned.streak)
    }

    @Test
    fun `a question too short to read changes nothing`() {
        // "ya", a figure read out. Nothing was learned, so neither the hint nor the streak moves.
        val held = ListenLanguage.next(Language.INDONESIAN, spoken = null, streak = 1)
        assertEquals(Language.INDONESIAN, held.language)
        assertEquals(1, held.streak)
    }

    @Test
    fun `the ear settles once it has switched`() {
        // Having moved to English, English questions keep it there and do not accumulate anything.
        val settled = ListenLanguage.next(Language.ENGLISH, Language.ENGLISH, streak = 0)
        assertEquals(Language.ENGLISH, settled.language)
        assertEquals(0, settled.streak)
    }
}
