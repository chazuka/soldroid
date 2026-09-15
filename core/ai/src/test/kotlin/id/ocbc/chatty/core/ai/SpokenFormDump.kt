package id.ocbc.chatty.core.ai

import java.io.File
import org.junit.Test

/**
 * Not an assertion — a listening aid.
 *
 * Writes the exact text the synthesizer receives for a set of real agent answers, so the spoken form
 * can be rendered to audio and judged by ear. Pronunciation is the one property of this pipeline that
 * cannot be checked by reading a diff.
 */
class SpokenFormDump {

    @Test
    fun `dump the spoken form of real answers`() {
        val samples = listOf(
            "Saldo total kamu Rp400.800.000, tersebar di rekening tabungan, dana perjalanan, " +
                "reksa dana, deposito, dan valas.",
            "Saldo tabungan kamu sekarang Rp3.240.000 — naik terus dari bulan ke bulan, bagus banget!",
            "Dua langganan yang sudah 90 hari nggak kamu sentuh itu totalnya Rp229.000 per bulan, " +
                "hampir Rp2.750.000 setahun.",
            "Portofolio kamu naik 1,2% bulan ini, dan turun 0,85% minggu lalu.",
            "Deposito kamu jatuh tempo 2026-09-10 dengan bunga 2,25% per tahun.",
            "Produk SBN-SR021 tersedia mulai 15 Oktober, minimal Rp1.000.000.",
            "Target iPad Air kamu Rp12.000.000, sekarang sudah terkumpul Rp3.240.000.",
            "Periode promo 2026-01-01 sampai 2026-12-31, cicilan mulai 2027-02-28.",
            "Nomor referensi 2026-99-10 bukan tanggal, jadi dibiarkan apa adanya.",
            "Tabungan kamu Rp2,7 juta dan target Rp180 juta.",
            "Nilainya Rp. 2.700.000 atau sekitar Rp2,7 jt.",
            "Dana darurat Rp500 ribu, portofolio Rp1,5 miliar.",
            "Your balance is Rp1,200,000 and it grew 1.2% this month.",
            "You saved 2,500 dollars, or about 1,5 percent of the total.",
        )

        val out = File("build/spoken-form.txt")
        out.parentFile.mkdirs()
        out.printWriter().use { w ->
            samples.forEach { raw ->
                // The language the app would resolve for this line, not one chosen here: the point
                // of the dump is to hear what actually reaches the synthesizer.
                val language = Language.detect(raw) ?: Language.INDONESIAN
                w.println("RAW: $raw")
                w.println("[${language.label}] SPOKEN: ${spokenForm(raw, language)}")
                w.println()
            }
        }
        println("wrote ${out.absolutePath}")
    }
}
