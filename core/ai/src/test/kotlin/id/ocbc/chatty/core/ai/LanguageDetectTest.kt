package id.ocbc.chatty.core.ai

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class LanguageDetectTest {

    @Test
    fun `an Indonesian answer is recognised`() {
        assertEquals(
            Language.INDONESIAN,
            Language.detect("Saldo total kamu Rp400.800.000, tersebar di beberapa rekening."),
        )
    }

    @Test
    fun `an English answer is recognised`() {
        assertEquals(
            Language.ENGLISH,
            Language.detect("Your balance is Rp400,800,000 across five accounts this month."),
        )
    }

    @Test
    fun `a question is enough to go on`() {
        assertEquals(Language.INDONESIAN, Language.detect("Berapa saldo tabungan saya sekarang?"))
        assertEquals(Language.ENGLISH, Language.detect("How much is in my savings account?"))
    }

    @Test
    fun `figures alone decide nothing`() {
        // No function words, so nothing to score. The caller keeps whatever it already had.
        assertNull(Language.detect("Rp2.500.000"))
        assertNull(Language.detect("2026-09-10"))
        assertNull(Language.detect(""))
    }

    @Test
    fun `an answer's opening clause is not enough to go on`() {
        // Why the turn's language is taken from the question and not from the first thing the model
        // says. A greeting carries no function words, so detection abstains — and the caller that
        // asked at that moment latches the fallback for the whole answer.
        assertNull(Language.detect("Halo Michael,"))
        assertNull(Language.detect("Hi Michael,"))
    }

    @Test
    fun `a spoken question is long enough to decide the turn`() {
        // The same turn, read from the question instead: both are unambiguous, which is what makes
        // the voice and the number speller right from the first clause rather than the second.
        assertEquals(Language.ENGLISH, Language.detect("How much money do I have in my account"))
        assertEquals(Language.INDONESIAN, Language.detect("Berapa uang yang ada di rekening saya"))
    }

    @Test
    fun `one borrowed word does not flip a sentence`() {
        assertEquals(
            Language.INDONESIAN,
            Language.detect("Pengeluaran kamu naik karena dining out dan subscription bulan ini."),
        )
    }
}
