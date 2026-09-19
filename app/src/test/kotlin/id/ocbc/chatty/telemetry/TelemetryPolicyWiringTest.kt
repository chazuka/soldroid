package id.ocbc.chatty.telemetry

import id.ocbc.chatty.core.ai.telemetry.TelemetryPolicy
import java.io.File
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Every setting in [TelemetryPolicy] is actually read by something.
 *
 * # Why this test exists
 *
 * `deviceContext` shipped as a field with a careful comment explaining what it controlled, and
 * controlled nothing: no code anywhere read it. Nothing failed, no test went red, and the only
 * symptom was a policy file that quietly lied.
 *
 * That is the worst possible defect in *this* file specifically. [TelemetryPolicy] exists to be the
 * one page a reviewer reads to decide what leaves a banking app, and a reviewer cannot tell a live
 * switch from a dead one by looking. A field that reads "off, so the record cannot leave" is worth
 * less than nothing if it is wired to nowhere — it buys confidence it has not earned.
 *
 * # What this checks, and what it does not
 *
 * It reads the adapter's source and asserts each policy property is mentioned by name. That is a
 * coarse check: it proves a field is referenced, not that it is honoured correctly. Behaviour is
 * pinned by the tests around it. What this catches is the specific failure that actually happened —
 * a field nobody wired at all — and it catches it the moment someone adds the next one.
 */
class TelemetryPolicyWiringTest {

    @Test
    fun `no policy setting is decorative`() {
        val adapter = File(SENTRY_ADAPTER)
        assertTrue(adapter.exists(), "expected the adapter at ${adapter.absolutePath}")
        val source = adapter.readText()

        val unwired = TelemetryPolicy::class.java.declaredFields
            .map { it.name }
            // Kotlin adds synthetic members to data classes; only the declared settings matter.
            .filterNot { it.startsWith("$") || it == "Companion" }
            .filterNot { source.contains(it) }

        assertTrue(
            unwired.isEmpty(),
            "these settings are documented as decisions but nothing reads them: $unwired",
        )
    }

    private companion object {
        /** Relative to the module directory, which is what Gradle runs unit tests from. */
        const val SENTRY_ADAPTER = "src/main/kotlin/id/ocbc/chatty/telemetry/SentryTelemetry.kt"
    }
}
