package id.ocbc.chatty.core.ai

import kotlinx.coroutines.flow.Flow
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Which brain answers, in builds that are not the one on this machine.
 *
 * # Why these are worth pinning
 *
 * Every rule here is about a build configured differently from the developer's: no Anthropic key,
 * no OpenAI key, a model switched off. None of those fail on the machine where the code is written,
 * and one of them used to throw — `get` fell back to `getValue(Brain.Default)`, which is safe only
 * for as long as the default happens to be the one brain every build constructs. Making Model 2 the
 * default made that assumption false, so the resolution is tested rather than assumed.
 */
class BrainsTest {

    @Test
    fun `the default is the fast stack`() {
        // Measured on a handset: 2.5-2.9s to the lips against 8.8-15.4s. A default is what most
        // people ever see, so it is the good end of that comparison.
        assertEquals(Brain.ANTHROPIC, Brain.Default)
    }

    @Test
    fun `a build with every key starts on the default`() {
        val brains = Brains(mapOf(Brain.KAMARTAJ to Stub, Brain.ANTHROPIC to Stub))

        assertEquals(Brain.ANTHROPIC, brains.default)
    }

    @Test
    fun `a build with no key for the default falls back rather than throwing`() {
        // The demo API's key is the only one this app has always needed, so this is a real build:
        // someone cloning the repo with only CHATTY_API_KEY set.
        val brains = Brains(mapOf(Brain.KAMARTAJ to Stub))

        assertEquals(Brain.KAMARTAJ, brains.default)
        assertSame(Stub, brains[Brain.ANTHROPIC])
    }

    @Test
    fun `asking for a brain this build cannot reach still answers`() {
        val brains = Brains(mapOf(Brain.KAMARTAJ to Stub))

        // A stale selection — restored from saved state after a rebuild that dropped a key — must
        // not take a turn down with it.
        assertSame(Stub, brains[Brain.OPENAI])
    }

    @Test
    fun `the chooser only offers what is both switched on and in the build`() {
        val brains = Brains(mapOf(Brain.KAMARTAJ to Stub, Brain.OPENAI to Stub))

        // OPENAI is constructed here but not offered, so it must not appear.
        assertEquals(listOf(Brain.KAMARTAJ), brains.available)
    }

    @Test
    fun `a brain that is offered but keyless is not on the chooser`() {
        val brains = Brains(mapOf(Brain.KAMARTAJ to Stub))

        assertTrue(Brain.ANTHROPIC.offered)
        assertTrue(Brain.ANTHROPIC !in brains.available)
    }

    private object Stub : ChatClient {
        override suspend fun agents(): List<AgentSummary> = emptyList()
        override fun reply(agentId: String, history: List<ChatMessage>, speaking: Language): Flow<String> =
            throw UnsupportedOperationException("not asked in these tests")
    }
}

/**
 * The labels stay anonymous, because they are what the comparison is reported by.
 *
 * Both the chooser and the telemetry group by this string. A vendor name reaching either one hands
 * whoever is judging the answers a prior about which should be better, which is the one thing this
 * app's own design goes out of its way to withhold.
 */
class BrainLabelTest {

    @Test
    fun `every label is a number, not a name`() {
        val notAnonymous = Brain.entries.filterNot { it.label.matches(Regex("""Model \d+""")) }

        assertEquals(emptyList(), notAnonymous, "these labels name something: $notAnonymous")
    }

    @Test
    fun `no label carries a vendor's name`() {
        val vendors = listOf("anthropic", "claude", "openai", "gpt", "deepseek", "kamartaj")
        val leaking = Brain.entries.filter { brain ->
            vendors.any { brain.label.contains(it, ignoreCase = true) }
        }

        assertEquals(emptyList(), leaking)
    }
}
