package id.ocbc.chatty.core.avatar

import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Before
import org.junit.Test

class LiveAvatarSessionTest {

    private lateinit var server: MockWebServer
    private lateinit var session: LiveAvatarSession

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
        session = LiveAvatarSession(
            http = OkHttpClient(),
            apiKey = "test-key",
            scope = CoroutineScope(SupervisorJob()),
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun stop() = server.close()

    /**
     * The settings this app pins have to survive serialization, which is not a given: they are
     * plain strings on a DTO, and a serializer configured to skip default values would drop them
     * and leave the provider to choose. It did exactly that, silently, for the life of this file —
     * so the assertion is on the bytes rather than on the object that built them.
     */
    @Test
    fun `the session is asked for high quality H264, and the request says so`() = runTest {
        server.enqueue(MockResponse(body = """{"code":100,"data":{"session_token":"t"}}"""))
        // The turn stops here: `start` is what needs a real room behind it, and minting the token
        // is the request under test.
        server.enqueue(MockResponse(code = 500, body = "{}"))

        runCatching { session.open("avatar-1") }

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains(""""quality":"high""""), body)
        assertTrue(body.contains(""""encoding":"H264""""), body)
    }
}
