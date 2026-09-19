package id.ocbc.chatty.core.ai

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class FrontierChatClientsTest {

    private lateinit var server: MockWebServer
    private lateinit var roster: CountingRoster
    private lateinit var records: CountingRecords
    private lateinit var client: AnthropicChatClient

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
        roster = CountingRoster()
        records = CountingRecords()
        client = AnthropicChatClient(
            calls = OkHttpClient(),
            apiKey = "test-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
            roster = roster,
            records = records,
        )
    }

    @After
    fun stop() = server.close()

    @Test
    fun `the model is told not to think before it answers`() = runTest {
        server.enqueue(streamOf("Halo."))

        client.reply("alvin", listOf(ChatMessage(ChatMessage.Role.USER, "halo"))).toList()

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains(""""thinking":{"type":"disabled"}"""), body.take(400))
    }

    @Test
    fun `the brief is sent as a cached block, and the question is not in it`() = runTest {
        server.enqueue(streamOf("Halo."))

        client.reply("alvin", listOf(ChatMessage(ChatMessage.Role.USER, "berapa saldo saya"))).toList()

        val body = server.takeRequest().body!!.utf8()
        // The breakpoint sits on the system block, so everything before it — the whole brief — is
        // the reusable prefix, and the question that changes every turn is behind it in `messages`.
        val system = body.substringAfter(""""system":""").substringBefore(""","messages":""")
        assertTrue(system.contains(""""cache_control":{"type":"ephemeral"}"""), system.take(200))
        assertTrue(system.contains("OCBC Indonesia"), system.take(200))
        assertTrue(!system.contains("berapa saldo saya"))
    }

    @Test
    fun `the brief is fetched once, not once per turn`() = runTest {
        repeat(3) {
            server.enqueue(streamOf("Halo."))
            client.reply("alvin", listOf(ChatMessage(ChatMessage.Role.USER, "halo"))).toList()
        }

        assertEquals(1, roster.calls.get())
        assertEquals(1, records.calls.get())
    }

    @Test
    fun `warming a persona leaves the first turn nothing to fetch`() = runTest {
        client.warm("alvin")
        assertEquals(1, roster.calls.get())
        assertEquals(1, records.calls.get())

        server.enqueue(streamOf("Halo."))
        client.reply("alvin", listOf(ChatMessage(ChatMessage.Role.USER, "halo"))).toList()

        assertEquals(1, roster.calls.get())
        assertEquals(1, records.calls.get())
    }

    @Test
    fun `a persona that cannot be briefed is not remembered as briefed`() = runTest {
        records.failTimes = 1

        client.warm("alvin")

        server.enqueue(streamOf("Halo."))
        client.reply("alvin", listOf(ChatMessage(ChatMessage.Role.USER, "halo"))).toList()

        // The failed warm must not have cached anything, or the turn would answer with a brief that
        // was never built.
        assertEquals(2, records.calls.get())
        assertTrue(server.takeRequest().body!!.utf8().contains("OCBC Indonesia"))
    }

    /** One Anthropic SSE stream carrying [text] as a single delta. */
    private fun streamOf(text: String) = MockResponse(
        body = """
            data: {"type":"content_block_delta","delta":{"type":"text_delta","text":"$text"}}

            data: [DONE]

        """.trimIndent(),
    )

    private class CountingRoster : ChatClient {
        val calls = AtomicInteger()

        override suspend fun agents(): List<AgentSummary> {
            calls.incrementAndGet()
            return listOf(AgentSummary("alvin", "Alvin", "a financial coach"))
        }

        override fun reply(agentId: String, history: List<ChatMessage>) =
            throw UnsupportedOperationException("the roster never answers a turn")
    }

    private class CountingRecords : CustomerRecords {
        val calls = AtomicInteger()

        /** How many of the next calls should fail, so a failed brief can be tested. */
        var failTimes = 0

        override suspend fun of(personaId: String): String {
            calls.incrementAndGet()
            if (failTimes > 0) {
                failTimes--
                throw ChatApiException(503, null, "customer record unavailable")
            }
            return """{"name":"Alvin","accounts":[]}"""
        }
    }
}
