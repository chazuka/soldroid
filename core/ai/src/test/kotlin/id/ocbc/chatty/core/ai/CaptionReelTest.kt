package id.ocbc.chatty.core.ai

import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The caption clock, covered where it has actually failed.
 *
 * Both of this machinery's bugs shipped: the reveal was cut off before it finished, leaving the rest
 * of an answer never shown, and the clauses were joined without the space the splitter removes. Both
 * are here as tests rather than as comments.
 */
class CaptionReelTest {

    /** One second of PCM at the one format LiveAvatar accepts. */
    private fun clause(text: String, seconds: Int) = SpokenCaption(text, 48_000L * seconds)

    @Test
    fun `nothing is shown until the lips move`() = runTest {
        val clauses = Channel<SpokenCaption>(Channel.UNLIMITED)
        val start = CompletableDeferred<Unit>()
        val shown = mutableListOf<String>()

        val job = launch { revealCaptions(clauses, awaitStart = { start.await() }, onCaption = shown::add) }
        clauses.send(clause("Saldo kamu aman.", 2))
        advanceTimeBy(5_000)

        assertEquals(emptyList(), shown, "a caption before the voice is the same desync, reversed")

        start.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("Saldo kamu aman."), shown)
        job.cancel()
    }

    @Test
    fun `each clause waits out the one before it`() = runTest {
        val clauses = Channel<SpokenCaption>(Channel.UNLIMITED)
        val shown = mutableListOf<String>()

        val job = launch { revealCaptions(clauses, awaitStart = {}, onCaption = shown::add) }
        clauses.send(clause("Satu.", 2))
        clauses.send(clause("Dua.", 3))
        clauses.send(clause("Tiga.", 1))

        advanceTimeBy(1_000)
        assertEquals(1, shown.size, "the second clause must not appear while the first is still being said")

        advanceTimeBy(1_500)
        assertEquals(2, shown.size)

        advanceTimeBy(3_000)
        assertEquals(3, shown.size)
        job.cancel()
    }

    @Test
    fun `closing the queue still shows every clause`() = runTest {
        // The shipped bug. The reveal is deliberately slower than the transfer, so it is still a
        // clause or more behind when the provider reports the lips have stopped. Cancelling there
        // froze the caption mid-answer and the rest was never shown; closing must drain instead.
        val clauses = Channel<SpokenCaption>(Channel.UNLIMITED)
        val shown = mutableListOf<String>()

        val job = launch { revealCaptions(clauses, awaitStart = {}, onCaption = shown::add) }
        clauses.send(clause("Satu.", 2))
        clauses.send(clause("Dua.", 2))
        clauses.send(clause("Tiga.", 2))
        clauses.close()

        advanceUntilIdle()
        assertEquals(3, shown.size, "every clause that was queued has to reach the screen")
        assertEquals("Satu. Dua. Tiga.", shown.last())
        job.cancel()
    }

    @Test
    fun `the caption only ever grows`() = runTest {
        val clauses = Channel<SpokenCaption>(Channel.UNLIMITED)
        val shown = mutableListOf<String>()

        val job = launch { revealCaptions(clauses, awaitStart = {}, onCaption = shown::add) }
        clauses.send(clause("Saldo kamu", 1))
        clauses.send(clause("Rp86.400.000.", 1))
        clauses.close()
        advanceUntilIdle()

        shown.zipWithNext { earlier, later ->
            assertEquals(true, later.startsWith(earlier), "an answer must never appear to shrink")
        }
        job.cancel()
    }

    @Test
    fun `clauses are rejoined with the space the splitter removed`() {
        assertEquals(
            "Berdasarkan angka Anda, membeli mobil",
            join("Berdasarkan angka Anda,", "membeli mobil"),
        )
    }

    @Test
    fun `a space already present is not doubled`() {
        assertEquals("Satu dua", join("Satu ", "dua"))
        assertEquals("Satu dua", join("Satu", " dua"))
    }

    @Test
    fun `the first clause gets no leading space`() {
        assertEquals("Satu", join("", "Satu"))
    }
}
