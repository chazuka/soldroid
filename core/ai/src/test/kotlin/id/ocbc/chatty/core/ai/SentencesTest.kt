package id.ocbc.chatty.core.ai

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SentencesTest {

    @Test
    fun `fragments are regrouped into sentences`() = runTest {
        val stream = flowOf("Halo Alya! Senang ", "ngobrol lagi. Saldo", "mu naik terus.")

        assertEquals(
            listOf("Halo Alya! Senang ngobrol lagi.", "Saldomu naik terus."),
            stream.sentences().toList(),
        )
    }

    @Test
    fun `a rupiah figure is never split at its thousands separators`() = runTest {
        val stream = flowOf("Saldo kamu Rp3.240.0", "00 akhir Juli. Naik terus.")

        assertEquals(
            listOf("Saldo kamu Rp3.240.000 akhir Juli.", "Naik terus."),
            stream.sentences().toList(),
        )
    }

    @Test
    fun `a short exclamation is joined to the clause after it`() = runTest {
        val stream = flowOf("Hai! Tabunganmu naik bulan ini.")

        assertEquals(listOf("Hai! Tabunganmu naik bulan ini."), stream.sentences().toList())
    }

    @Test
    fun `the last sentence is flushed even without trailing punctuation`() = runTest {
        val stream = flowOf("Saldo kamu naik terus bulan ini. Ada yang mau ditanya")

        assertEquals(
            listOf("Saldo kamu naik terus bulan ini.", "Ada yang mau ditanya"),
            stream.sentences().toList(),
        )
    }

    @Test
    fun `an empty stream yields nothing`() = runTest {
        assertEquals(emptyList(), flowOf<String>().sentences().toList())
    }

    @Test
    fun `concatenating the sentences preserves every word`() = runTest {
        val answer = "Halo Alya! Aku lihat saldo tabunganmu Rp3.240.000 akhir Juli. " +
            "Naik terus tiga bulan berturut-turut. Kamu hebat banget, ya!"
        val words = answer.split(" ").filter(String::isNotEmpty)

        val rebuilt = answer.chunked(7).asFlowOfChunks().sentences().toList()

        assertEquals(words, rebuilt.joinToString(" ").split(" ").filter(String::isNotEmpty))
    }

    @Test
    fun `the opening chunk breaks at a clause so the voice starts sooner`() = runTest {
        // The real answer that exposed this: the customer waited 4.4s past the first token because
        // the opening sentence ran to a full stop before anything could be synthesised.
        val answer = "Saldo tabungan kamu sekarang Rp3.240.000 — naik terus dari bulan ke bulan, " +
            "bagus banget! Mau aku tunjukkan cara bikin target menabung?"

        val chunks = flowOf(answer).sentences().toList()

        assertEquals("Saldo tabungan kamu sekarang Rp3.240.000 —", chunks.first())
        assertTrue(chunks.size >= 2, "expected the remainder to follow, got $chunks")
    }

    @Test
    fun `only the first chunk breaks on a clause, the rest keep whole sentences`() = runTest {
        val answer = "Halo Alya, senang ngobrol lagi. Saldomu naik, terus begitu ya. Sampai nanti."

        val chunks = flowOf(answer).sentences().toList()

        // The opener may break at the comma; everything after it must end on a sentence terminator.
        chunks.drop(1).forEach { chunk ->
            assertTrue(
                chunk.last() in ".!?…",
                "chunk after the first should end a sentence, got \"$chunk\"",
            )
        }
    }

    @Test
    fun `a decimal comma is not a clause break`() = runTest {
        // Indonesian writes decimals with a comma. The whitespace guard is what keeps "1,2%" whole.
        val chunks = flowOf("Portofolio kamu naik 1,2% bulan ini dan tetap stabil sepanjang kuartal.")
            .sentences().toList()

        assertTrue(chunks.none { it.endsWith("1,") }, "split inside a decimal: $chunks")
        assertTrue(chunks.joinToString(" ").contains("1,2%"))
    }

    @Test
    fun `a short opener is still held back rather than spoken alone`() = runTest {
        val chunks = flowOf("Hai, tabunganmu naik bulan ini dan itu kabar bagus.").sentences().toList()

        assertTrue(chunks.first().length >= "Hai,".length + 4, "opener too short: $chunks")
    }

    private fun List<String>.asFlowOfChunks() = flowOf(*toTypedArray())

    @Test
    fun `the opening chunk breaks after a figure rather than waiting for punctuation`() = runTest {
        // The shape the prompt asks for: lead with the number, then explain it. Waiting for the
        // comma would hold the first sound back by another 27 characters of silence.
        val chunks = "Total sekitar Rp400.800.000 tersebar di rekening gaji, dana liburan."
            .asCharacterStream().sentences().toList()

        assertEquals("Total sekitar Rp400.800.000", chunks.first())
    }

    @Test
    fun `a figure is never split down the middle`() = runTest {
        // The break only lands after whitespace, and a figure contains none. Handing half of
        // "Rp3.240.000" to the number speller would say something that is not the balance.
        val chunks = "Saldo kamu Rp3.240.000 saat ini. Naik sedikit.".asCharacterStream().sentences().toList()

        assertEquals("Saldo kamu Rp3.240.000", chunks.first())
        assertTrue(chunks.none { it.endsWith("Rp3.") || it.startsWith("240") })
    }

    @Test
    fun `a figure too early to be worth saying is passed over`() = runTest {
        // Below the floor the chunk would be two words, which is an avatar clearing its throat
        // rather than answering. The next boundary is taken instead.
        val chunks = "Ada 3 produk yang cocok untukmu, dan semuanya likuid."
            .asCharacterStream().sentences().toList()

        assertTrue(chunks.first().length >= 18, "opened with: ${chunks.first()}")
    }

    @Test
    fun `only the opening chunk breaks on a figure`() = runTest {
        // Everything after the first is spoken while the avatar is already talking, so it can
        // afford to wait for punctuation — and punctuation is what gives it intonation.
        val chunks = "Halo, ini ringkasannya. Saldo kamu Rp3.240.000 saat ini dan stabil."
            .asCharacterStream().sentences().toList()

        assertTrue(chunks.drop(1).none { it.trimEnd().last().isDigit() }, "chunks: $chunks")
    }

    /** One character at a time: the worst case, and the closest to how tokens actually arrive. */
    private fun String.asCharacterStream() = flow { forEach { emit(it.toString()) } }
}
