package id.ocbc.chatty.core.ai.telemetry

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class TelemetryPolicyTest {

    /**
     * The four settings that decide whether a customer's record can leave the handset.
     *
     * Pinned as a test rather than trusted to a default value, because each one is a field an SDK
     * would happily turn on for us and none of them announces itself when it flips. A future change
     * to any of these should have to argue with this test first.
     */
    @Test
    fun `the shipped policy sends nothing that could carry a customer's record`() {
        val policy = TelemetryPolicy.Default

        assertFalse(policy.captureFailedRequests, "a failed record fetch would carry the record")
        assertFalse(policy.attachScreenshot, "the screen is a transcript of this customer's finances")
        assertFalse(policy.attachViewHierarchy, "a Compose tree carries the text inside it")
        assertFalse(policy.sendDefaultPii, "IP, headers, cookies and request bodies")
    }

    @Test
    fun `the shipped policy keeps what makes an error readable`() {
        val policy = TelemetryPolicy.Default

        assertTrue(policy.httpBreadcrumbs)
        assertTrue(policy.scrubUrls)
        assertTrue(policy.deviceContext)
        // Hundreds of turns a day: sampling a population this small is how a misbehaving handset
        // ends up with three data points.
        assertEquals(1.0, policy.sampleRate, 0.0)
    }

    @Test
    fun `a sample rate outside zero to one is refused where it is written, not where it is used`() {
        assertThrows(IllegalArgumentException::class.java) { TelemetryPolicy(sampleRate = 1.5) }
        assertThrows(IllegalArgumentException::class.java) { TelemetryPolicy(sampleRate = -0.1) }
    }

    @Test
    fun `recording nothing is a rate of zero, not a separate flag`() {
        assertTrue(TelemetryPolicy(sampleRate = 0.0).recordsNothing)
        assertFalse(TelemetryPolicy.Default.recordsNothing)
    }

    @Test
    fun `which customer a call was about does not travel, but which endpoint it was does`() {
        assertEquals(
            "https://kamartaj.xyz/api/customers/{persona}",
            scrubUrl("https://kamartaj.xyz/api/customers/alvin"),
        )
    }

    @Test
    fun `a voice id is a persona by another name`() {
        assertEquals(
            "https://api.elevenlabs.io/v1/text-to-speech/{voice}/stream",
            scrubUrl("https://api.elevenlabs.io/v1/text-to-speech/EXAVITQu4vr4xnSDxMaL/stream?output_format=pcm_24000"),
        )
    }

    @Test
    fun `query strings go whole, because they carry keys on these vendors`() {
        assertEquals(
            "https://api.elevenlabs.io/v1/health",
            scrubUrl("https://api.elevenlabs.io/v1/health?token=sk-secret"),
        )
    }

    @Test
    fun `a bare path is scrubbed too, not just a whole URL`() {
        // Breadcrumbs carry the address as a path rather than a full URL, and on a real device that
        // difference shipped an unscrubbed voice id while the spans beside it were clean.
        assertEquals(
            "/v1/text-to-speech/{voice}/stream",
            scrubUrl("/v1/text-to-speech/cjVigY5qzO86Huf0OWal/stream"),
        )
        assertEquals("/api/customers/{persona}", scrubUrl("/api/customers/alvin"))
    }

    @Test
    fun `a URL with nobody's name in it is left alone`() {
        val url = "https://api.anthropic.com/v1/messages"
        assertEquals(url, scrubUrl(url))
    }

    @Test
    fun `an identifier at the very end of a path is still replaced`() {
        // The record endpoint is called exactly this way — no trailing slash, nothing after the id
        // — so the "find the next slash" rule has to cope with there not being one.
        assertEquals(
            "https://kamartaj.xyz/api/customers/{persona}",
            scrubUrl("https://kamartaj.xyz/api/customers/emma"),
        )
    }
}
