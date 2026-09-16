package id.ocbc.chatty.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records which code paths a cold start actually runs, so they can be compiled ahead of time.
 *
 * # Why bother
 *
 * Without a profile, everything on the launch path is interpreted until ART decides it is hot —
 * which, for a screen the customer sees once and then leaves, is never. The first impression of this
 * app is a list that either appears or stutters, and this is the cheapest way to make it appear.
 *
 * # What it exercises, and what it deliberately does not
 *
 * Launch and the agent list, and nothing past it. Opening a conversation would be a more complete
 * profile and a much worse test: it bills a LiveAvatar session and a synthesis every time anyone
 * runs it, and it fails whenever the network does. The stage's cost is video decode in native code,
 * which a Baseline Profile cannot speed up anyway.
 *
 * Run with `./gradlew :app:generateBaselineProfile` against a connected device.
 */
@RunWith(AndroidJUnit4::class)
class StartupProfileGenerator {

    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun startup() = rule.collect(
        packageName = PACKAGE_NAME,
        // Also emit a startup profile, which orders the classes in the dex so the launch path is
        // read in one contiguous sweep rather than seeking across the file.
        includeInStartupProfile = true,
    ) {
        pressHome()
        startActivityAndWait()
        // The list arrives over the network; waiting for idle keeps the profile honest about what
        // rendering a populated list costs, rather than profiling an empty frame.
        device.waitForIdle()
    }

    private companion object {
        // The applicationId from `:app`, not this file's own package: UI Automator launches the
        // app the way the launcher does, by install id. Keep in step with `app/build.gradle.kts`.
        const val PACKAGE_NAME = "id.ocbc.sol"
    }
}
