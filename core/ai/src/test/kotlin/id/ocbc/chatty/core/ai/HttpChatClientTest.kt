package id.ocbc.chatty.core.ai

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class HttpChatClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: HttpChatClient

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
        client = HttpChatClient(
            calls = OkHttpClient(),
            baseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
        )
    }

    @After
    fun stop() = server.close()

    @Test
    fun `agents carry the display name and tagline the API supplies`() = runTest {
        server.enqueue(MockResponse(body = MODELS_BODY))

        val agents = client.agents()

        assertEquals(listOf("emma", "daniel"), agents.map { it.id })
        assertEquals("Emma", agents[0].displayName)
        assertEquals("a warm money buddy", agents[0].tagline)
    }

    @Test
    fun `an agent with no metadata still gets a readable name`() = runTest {
        server.enqueue(MockResponse(body = """{"object":"list","data":[{"id":"sophia"}]}"""))

        assertEquals("Sophia", client.agents().single().displayName)
    }

    @Test
    fun `the bearer key rides the Authorization header`() = runTest {
        server.enqueue(MockResponse(body = MODELS_BODY))

        client.agents()

        assertEquals("Bearer test-key", server.takeRequest().headers["Authorization"])
    }

    @Test
    fun `reply emits the answer as it streams and rebuilds it in order`() = runTest {
        server.enqueue(sse(REPLY_CHUNKS))

        val parts = client.reply("emma", listOf(ChatMessage(ChatMessage.Role.USER, "halo"))).toList()

        assertEquals(listOf("Tabungan", "mu ", "naik."), parts)
        assertEquals("Tabunganmu naik.", parts.joinToString(""))
    }

    @Test
    fun `keep-alives and contentless chunks are skipped, not failed on`() = runTest {
        server.enqueue(
            sse(
                listOf(
                    ": ping",
                    "",
                    """data: {"choices":[{"delta":{"role":"assistant"}}]}""",
                    """data: {"choices":[{"delta":{"content":"Halo."}}]}""",
                    "data: [DONE]",
                ),
            ),
        )

        assertEquals(
            listOf("Halo."),
            client.reply("emma", listOf(ChatMessage(ChatMessage.Role.USER, "halo"))).toList(),
        )
    }

    @Test
    fun `reply asks for a stream`() = runTest {
        server.enqueue(sse(REPLY_CHUNKS))

        client.reply("emma", listOf(ChatMessage(ChatMessage.Role.USER, "halo"))).toList()

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains(""""stream":true"""))
    }

    @Test
    fun `a failure keeps the message the API wrote`() = runTest {
        server.enqueue(MockResponse(code = 404, body = ERROR_BODY))

        val failure = assertThrows(ChatApiException::class.java) {
            kotlinx.coroutines.runBlocking {
                client.reply("nope", listOf(ChatMessage(ChatMessage.Role.USER, "halo"))).toList()
            }
        }

        assertEquals(404, failure.status)
        assertEquals("model_not_found", failure.code)
        assertTrue(failure.message!!.contains("does not exist"))
    }

    @Test
    fun `an unparseable failure body falls back to the status`() = runTest {
        server.enqueue(MockResponse(code = 502, body = "<html>bad gateway</html>"))

        val failure = assertThrows(ChatApiException::class.java) {
            kotlinx.coroutines.runBlocking { client.agents() }
        }

        assertEquals(502, failure.status)
        assertTrue(failure.message!!.contains("502"))
    }

    @Test
    fun `a stream that carries no content is a failure, not an empty bubble`() = runTest {
        server.enqueue(sse(listOf("data: [DONE]")))

        assertThrows(ChatApiException::class.java) {
            kotlinx.coroutines.runBlocking {
                client.reply("emma", listOf(ChatMessage(ChatMessage.Role.USER, "halo"))).toList()
            }
        }
    }

    private fun sse(lines: List<String>) = MockResponse.Builder()
        .code(200)
        .setHeader("Content-Type", "text/event-stream")
        .body(lines.joinToString("\n") + "\n")
        .build()

    private companion object {
        const val MODELS_BODY = """
            {"object":"list","data":[
              {"id":"emma","object":"model","x_avatar":{"display_name":"Emma","tagline":"a warm money buddy"}},
              {"id":"daniel","object":"model","x_avatar":{"display_name":"Daniel","tagline":"a coach"}}
            ]}
        """

        val REPLY_CHUNKS = listOf(
            """data: {"choices":[{"delta":{"content":"Tabungan"}}]}""",
            """data: {"choices":[{"delta":{"content":"mu "}}]}""",
            """data: {"choices":[{"delta":{"content":"naik."}}]}""",
            "data: [DONE]",
        )

        const val ERROR_BODY = """
            {"error":{"message":"The model `nope` does not exist.","type":"invalid_request_error",
             "code":"model_not_found","param":"model"}}
        """
    }
}
