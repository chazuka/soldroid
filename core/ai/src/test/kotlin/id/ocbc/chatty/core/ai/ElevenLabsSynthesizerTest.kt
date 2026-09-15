package id.ocbc.chatty.core.ai

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class ElevenLabsSynthesizerTest {

    private lateinit var server: MockWebServer
    private lateinit var synthesizer: ElevenLabsSynthesizer

    @Before
    fun start() {
        server = MockWebServer().apply { start() }
        synthesizer = ElevenLabsSynthesizer(
            calls = OkHttpClient(),
            apiKey = "test-key",
            baseUrl = server.url("/").toString().trimEnd('/'),
        )
    }

    @After
    fun stop() = server.close()

    @Test
    fun `audio is cut into one-second frames, remainder last`() = runTest {
        val frameBytes = ElevenLabsSynthesizer.FRAME_BYTES.toInt()
        val remainder = 1_234
        server.enqueue(pcmResponse(frameBytes * 2 + remainder))

        val frames = synthesizer.speak("voice", "halo", Language.INDONESIAN).toList()

        assertEquals(listOf(frameBytes, frameBytes, remainder), frames.map { it.size })
    }

    @Test
    fun `audio shorter than one frame is still emitted`() = runTest {
        server.enqueue(pcmResponse(512))

        assertEquals(listOf(512), synthesizer.speak("voice", "halo", Language.INDONESIAN).toList().map { it.size })
    }

    @Test
    fun `the key rides a header and the format is pinned to PCM 24k`() = runTest {
        server.enqueue(pcmResponse(64))

        synthesizer.speak("velora", "halo", Language.INDONESIAN).toList()

        val request = server.takeRequest()
        assertEquals("test-key", request.headers["xi-api-key"])
        assertTrue(request.target.startsWith("/v1/text-to-speech/velora/stream"))
        assertTrue(request.target.contains("output_format=pcm_24000"))
    }

    @Test
    fun `a rejected request fails before any frame is emitted`() = runTest {
        server.enqueue(MockResponse(code = 401, body = """{"detail":"invalid api key"}"""))

        val failure = assertThrows(SynthesisException::class.java) {
            kotlinx.coroutines.runBlocking { synthesizer.speak("voice", "halo", Language.INDONESIAN).toList() }
        }

        assertTrue(failure.message!!.contains("401"))
    }

    @Test
    fun `a synthesis that renders nothing is a failure, not a silent avatar`() = runTest {
        server.enqueue(pcmResponse(0))

        assertThrows(SynthesisException::class.java) {
            kotlinx.coroutines.runBlocking { synthesizer.speak("voice", "halo", Language.INDONESIAN).toList() }
        }
    }

    @Test
    fun `an empty line is refused without spending a call`() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { synthesizer.speak("voice", "   ", Language.INDONESIAN).toList() }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `the synthesizer is sent the spoken form, not the printed one`() = runTest {
        server.enqueue(pcmResponse(64))

        synthesizer.speak("voice", "Saldo kamu Rp3.240.000.", Language.INDONESIAN).toList()

        val body = server.takeRequest().body!!.utf8()
        assertTrue(body.contains("tiga juta dua ratus empat puluh ribu rupiah"))
        assertTrue(!body.contains("3.240.000"))
    }

    private fun pcmResponse(bytes: Int) =
        MockResponse.Builder().code(200).body(Buffer().write(ByteArray(bytes))).build()
}
