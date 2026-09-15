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
    fun `one borrowed word does not flip a sentence`() {
        assertEquals(
            Language.INDONESIAN,
            Language.detect("Pengeluaran kamu naik karena dining out dan subscription bulan ini."),
        )
    }
}
