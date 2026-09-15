package id.ocbc.chatty.core.ai

import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.Test

class SpokenFormTest {

    @Test
    fun `rupiah is spelled and the unit moves after the amount`() {
        assertEquals(
            "tiga juta dua ratus empat puluh ribu rupiah",
            speakNumber("Rp3.240.000"),
        )
    }

    @Test
    fun `the irregular se- forms are used where Indonesian uses them`() {
        assertEquals("seratus", speakNumber("100"))
        assertEquals("seribu", speakNumber("1.000"))
        assertEquals("sepuluh", speakNumber("10"))
        assertEquals("sebelas", speakNumber("11"))
        // …but never before juta: "satu juta", not "sejuta".
        assertEquals("satu juta", speakNumber("1.000.000"))
    }

    @Test
    fun `teens and tens`() {
        assertEquals("dua belas", speakNumber("12"))
        assertEquals("sembilan belas", speakNumber("19"))
        assertEquals("dua puluh", speakNumber("20"))
        assertEquals("delapan puluh lima", speakNumber("85"))
    }

    @Test
    fun `a large balance keeps every magnitude`() {
        assertEquals(
            "delapan ratus lima puluh dua juta tiga ratus ribu rupiah",
            speakNumber("Rp 852.300.000"),
        )
    }

    @Test
    fun `decimals are read digit by digit and comma is the decimal mark`() {
        assertEquals("satu koma dua persen", speakNumber("1,2%"))
        assertEquals("nol koma delapan lima persen", speakNumber("0,85%"))
    }

    @Test
    fun `a negative is announced before the figure`() {
        assertEquals("minus nol koma delapan persen", speakNumber("-0,8%"))
    }

    @Test
    fun `a figure past the ceiling is refused rather than guessed`() {
        assertNull(speakNumber("9.999.999.999.999.999"))
    }

    @Test
    fun `an ISO date is said as a date, not as three quantities`() {
        assertEquals(
            "Jatuh tempo sepuluh September dua ribu dua puluh enam.",
            spokenForm("Jatuh tempo 2026-09-10.", Language.INDONESIAN),
        )
    }

    @Test
    fun `a leading zero in the day does not become part of the number`() {
        assertEquals("satu Januari dua ribu dua puluh enam", spokenForm("2026-01-01", Language.INDONESIAN))
    }

    @Test
    fun `a possessive clitic does not stop a figure being spelled`() {
        // Indonesian cannot attach a clitic to digits, so the hyphen is written instead. Refusing to
        // spell these left the amount being read out as characters.
        assertEquals("dua belas juta rupiahmu", spokenForm("Rp12.000.000-mu", Language.INDONESIAN))
        assertEquals("tiga juta rupiahnya", spokenForm("Rp3.000.000-nya", Language.INDONESIAN))
        assertEquals(
            "Tabunganmu tinggal dua juta rupiahku.",
            spokenForm("Tabunganmu tinggal Rp2.000.000-ku.", Language.INDONESIAN),
        )
    }

    @Test
    fun `a hyphen between two figures still leaves them alone`() {
        assertEquals("Referensi 2026-99-10.", spokenForm("Referensi 2026-99-10.", Language.INDONESIAN))
        assertEquals("kode 12-34", spokenForm("kode 12-34", Language.INDONESIAN))
    }

    @Test
    fun `a span that is shaped like a date but is not one keeps its digits`() {
        // Month 99 exists in no calendar, so this is a reference number that happens to be
        // punctuated like a date. Saying it as one would invent a fact.
        assertEquals("Referensi 2026-99-10.", spokenForm("Referensi 2026-99-10.", Language.INDONESIAN))
        assertEquals("Referensi 2026-09-40.", spokenForm("Referensi 2026-09-40.", Language.INDONESIAN))
    }

    @Test
    fun `a longer digit run is not mistaken for a date hiding inside it`() {
        assertEquals("12026-09-10", spokenForm("12026-09-10", Language.INDONESIAN))
        assertEquals("2026-09-101", spokenForm("2026-09-101", Language.INDONESIAN))
    }

    @Test
    fun `product ids are invisible to the speller`() {
        assertEquals("Produk SBN-SR021 tersedia.", spokenForm("Produk SBN-SR021 tersedia.", Language.INDONESIAN))
    }

    @Test
    fun `a whole sentence is rewritten in place`() {
        assertEquals(
            "Saldo kamu tiga juta dua ratus empat puluh ribu rupiah, naik satu koma dua persen.",
            spokenForm("Saldo kamu Rp3.240.000, naik 1,2%.", Language.INDONESIAN),
        )
    }

    @Test
    fun `basis points get their unit before the general speller sees the digits`() {
        assertEquals("Naik plus dua belas basis poin.", spokenForm("Naik +12bp.", Language.INDONESIAN))
    }

    @Test
    fun `text with no numbers is returned unchanged`() {
        val line = "Kamu hebat banget, ya! Ada yang mau kamu tanyakan?"
        assertEquals(line, spokenForm(line, Language.INDONESIAN))
    }

    @Test
    fun `canonicalize reads Indonesian separators the Indonesian way`() {
        assertEquals("3240000", canonicalize("Rp3.240.000"))
        assertEquals("1.2", canonicalize("1,2%"))
        assertEquals("-1234", canonicalize("-Rp1.234"))
        assertEquals("-1234", canonicalize("Rp-1.234"))
        assertEquals("0", canonicalize("0,00"))
    }

    @Test
    fun `every figure a persona is likely to speak round-trips`() {
        val figures = listOf(
            "Rp1.180.000", "Rp2.100.000", "Rp900.000", "Rp12.000.000", "Rp1.050.000",
            "Rp45.000", "43%", "2,25%", "Rp600.000", "Rp3.240.000", "Rp550.000",
        )
        for (figure in figures) {
            val said = speakNumber(figure)
            assertEquals(true, said != null, "no spelling for $figure")
        }
    }

    @Test
    fun `a scale word takes the currency to the far side of it`() {
        assertEquals("dua koma tujuh juta rupiah", spokenForm("Rp2,7 juta", Language.INDONESIAN))
        assertEquals("lima ratus ribu rupiah", spokenForm("Rp500 ribu", Language.INDONESIAN))
        assertEquals("satu koma lima miliar rupiah", spokenForm("Rp1,5 miliar", Language.INDONESIAN))
    }

    @Test
    fun `the written abbreviations are read as the words they stand for`() {
        assertEquals("dua koma tujuh juta rupiah", spokenForm("Rp2,7 jt", Language.INDONESIAN))
        assertEquals("lima ratus ribu rupiah", spokenForm("Rp500 rb", Language.INDONESIAN))
        assertEquals("satu koma lima miliar rupiah", spokenForm("Rp1,5 milyar", Language.INDONESIAN))
    }

    @Test
    fun `a scale word without a currency stays a bare quantity`() {
        assertEquals("dua koma tujuh juta", spokenForm("2,7 juta", Language.INDONESIAN))
    }

    @Test
    fun `the currency symbol may be written with a full stop`() {
        assertEquals(
            "dua juta tujuh ratus ribu rupiah",
            spokenForm("Rp. 2.700.000", Language.INDONESIAN),
        )
    }

    @Test
    fun `an Indonesian amount inside English text is spelled in English`() {
        // Re-punctuating to "86,400,000" was the old behaviour, and it relied on the synthesizer
        // normalising numerals. Flash v2.5 does not, and read the digits as "two thousand thousand".
        assertEquals(
            "Your salary account holds eighty-six million four hundred thousand rupiah.",
            spokenForm("Your salary account holds Rp86.400.000.", Language.ENGLISH),
        )
        assertEquals("two million rupiah", spokenForm("Rp.2.000.000", Language.ENGLISH))
        assertEquals("eighty-six million four hundred thousand", spokenForm("86.400.000", Language.ENGLISH))
    }

    @Test
    fun `an English-written figure is read the English way`() {
        // "1,200" in English text is twelve hundred. Reading that comma as an Indonesian decimal
        // point would divide it by a thousand, which is the mistake this whole file exists to avoid.
        assertEquals("one thousand two hundred", spokenForm("1,200", Language.ENGLISH))
        assertEquals("one point two percent", spokenForm("1.2%", Language.ENGLISH))
        assertEquals("two thousand five hundred dollars", spokenForm("2,500 dollars", Language.ENGLISH))
        assertEquals("one million two hundred thousand rupiah", spokenForm("Rp1,200,000", Language.ENGLISH))
        assertEquals(
            "two million five hundred thousand point five",
            spokenForm("2,500,000.50", Language.ENGLISH),
        )
    }

    @Test
    fun `the two ambiguous shapes get the reading canonicalizeEnglish states`() {
        // A single dot with three digits after it: a decimal in English, thousands when the figure
        // carries a currency that has no sub-unit anyone quotes.
        assertEquals("one point two three four", spokenForm("1.234", Language.ENGLISH))
        assertEquals("one thousand two hundred thirty-four rupiah", spokenForm("Rp1.234", Language.ENGLISH))
        // A single comma with three digits after it is English grouping; anything else is a decimal.
        assertEquals("one thousand two hundred", spokenForm("1,200", Language.ENGLISH))
        assertEquals("one point two", spokenForm("1,2", Language.ENGLISH))
    }

    @Test
    fun `canonicalizeEnglish refuses a figure written in no convention at all`() {
        assertNull(canonicalizeEnglish("1.2.3"))
        assertNull(canonicalizeEnglish("12,34,567"))
    }

    @Test
    fun `an Indonesian scale word in English text is translated, not left in place`() {
        // Without this the general speller puts the currency at the end of its own token and says
        // "two point seven rupiah juta", which is not a sum of money in any language.
        assertEquals("two point seven million rupiah", spokenForm("Rp2,7 juta", Language.ENGLISH))
        assertEquals("five hundred thousand rupiah", spokenForm("Rp500 ribu", Language.ENGLISH))
        // "miliar" is 10^9 and so is "billion" — both number systems are short scale.
        assertEquals("one point five billion rupiah", spokenForm("Rp1,5 miliar", Language.ENGLISH))
        assertEquals("two point seven million", spokenForm("2,7 juta", Language.ENGLISH))
    }

    @Test
    fun `a sign and a zero survive the English speller`() {
        assertEquals(
            "minus one million five hundred thousand rupiah",
            spokenForm("-Rp1.500.000", Language.ENGLISH),
        )
        assertEquals("zero rupiah", spokenForm("Rp0", Language.ENGLISH))
    }

    @Test
    fun `English text with no numbers is returned unchanged`() {
        val english = "How much did I save this month, and is that enough?"
        assertEquals(english, spokenForm(english, Language.ENGLISH))
    }

    @Test
    fun `a figure too large to spell is left as digits`() {
        // One past the ceiling. Awkward read beats a misstated fortune.
        assertEquals("1.000.000.000.000.000", spokenForm("1.000.000.000.000.000", Language.ENGLISH))
    }

    @Test
    fun `a model name's trailing letter is separated from its number`() {
        assertEquals("The iPhone seventeen E is out.", spokenForm("The iPhone 17e is out.", Language.ENGLISH))
        assertEquals("five G", spokenForm("5G", Language.ENGLISH))
        // The same rule in the language the rest of the sentence is in.
        assertEquals("iPhone tujuh belas E", spokenForm("iPhone 17e", Language.INDONESIAN))
    }

    @Test
    fun `the model-name rule does not reach an ordinal, a unit or an identifier`() {
        assertEquals("1st", spokenForm("1st", Language.ENGLISH))
        assertEquals("2nd", spokenForm("2nd", Language.ENGLISH))
        assertEquals("10km", spokenForm("10km", Language.ENGLISH))
        assertEquals("SBN-SR021", spokenForm("SBN-SR021", Language.ENGLISH))
        assertEquals("COVID-19", spokenForm("COVID-19", Language.ENGLISH))
        // It must not bite the "2" out of an amount, either.
        assertEquals("two million rupiah", spokenForm("Rp2.000.000", Language.ENGLISH))
    }

    @Test
    fun `an ISO date in English text is said as a date`() {
        assertEquals(
            "It matures the tenth of September two thousand twenty-six.",
            spokenForm("It matures 2026-09-10.", Language.ENGLISH),
        )
        // Day-month-year, the order it was written in and the order [speakDates] says it in.
        assertEquals(
            "the first of January two thousand twenty-six",
            spokenForm("2026-01-01", Language.ENGLISH),
        )
        assertEquals(
            "the thirty-first of December two thousand twenty-six",
            spokenForm("2026-12-31", Language.ENGLISH),
        )
    }

    @Test
    fun `an English date that is not one is left as digits`() {
        assertEquals("Reference 2026-99-10.", spokenForm("Reference 2026-99-10.", Language.ENGLISH))
        assertEquals("Reference 2026-09-40.", spokenForm("Reference 2026-09-40.", Language.ENGLISH))
        // A longer run of digits on either side is a reference number, not a date.
        assertEquals("12026-09-10", spokenForm("12026-09-10", Language.ENGLISH))
        assertEquals("2026-09-101", spokenForm("2026-09-101", Language.ENGLISH))
    }

    @Test
    fun `every day of every month is a date this can say`() {
        for (month in 1..12) {
            for (day in 1..31) {
                val iso = "2026-%02d-%02d".format(month, day)
                val said = spokenForm(iso, Language.ENGLISH)
                assertEquals(false, said.any(Char::isDigit), "$iso was left as digits: $said")
            }
        }
    }

    @Test
    fun `basis points are spoken as their unit, and the unit is inflected`() {
        assertEquals(
            "Rates moved plus twelve basis points.",
            spokenForm("Rates moved +12bp.", Language.ENGLISH),
        )
        assertEquals(
            "It fell minus twenty-five basis points.",
            spokenForm("It fell -25bp.", Language.ENGLISH),
        )
        // English inflects where Indonesian's "basis poin" does not.
        assertEquals("one basis point", spokenForm("1bp", Language.ENGLISH))
        assertEquals("minus zero point five basis points", spokenForm("-0,5 bps", Language.ENGLISH))
    }

    @Test
    fun `every English spelling parses back to the digits it came from`() {
        // The speller returns null rather than a guess, so a figure that survives with no digits
        // left in it is one the inverse agreed with.
        val values = (0L..1_500L).toList() + listOf(
            9_999L, 10_000L, 100_001L, 999_999L, 1_000_000L, 1_234_567L, 86_400_000L,
            999_999_999L, 1_000_000_000L, 999_999_999_999L, 1_000_000_000_000L, 999_999_999_999_999L,
        )
        for (value in values) {
            val said = spokenForm(value.toString(), Language.ENGLISH)
            assertEquals(false, said.any(Char::isDigit), "$value was left as digits: $said")
        }
    }
}