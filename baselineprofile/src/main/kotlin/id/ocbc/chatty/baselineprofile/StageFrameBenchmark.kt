package id.ocbc.chatty.baselineprofile

import android.os.SystemClock
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the avatar stage costs in frames while an answer is streaming in and being spoken.
 *
 * # Why this exists
 *
 * The stage was reported as laggy, and the suspected cause is that every streamed token writes a new
 * `CompanionUiState` — which recomposes the whole stage subtree, level bars and badge included,
 * dozens of times a second. That is a plausible story, and a plausible story is not a measurement.
 * This turns it into one: run it, change the thing, run it again, and the percentiles say whether it
 * helped.
 *
 * Frames are only counted inside `measureBlock`, so the navigation that gets to the stage sits in
 * `setupBlock` and is excluded. What is measured is the observation window: the stage, with an
 * answer arriving into it.
 *
 * # What running this costs
 *
 * Real money, every iteration. There is no way to exercise this path without opening a LiveAvatar
 * session, calling the chat API and synthesizing speech — that *is* the path. Keep [ITERATIONS] low
 * and do not wire this into CI on a timer.
 *
 * It also needs the network, so a failure here is as likely to be a bad connection as a regression.
 * Read a single bad run as "run it again", not as a result.
 *
 * # Running it
 *
 * ```
 * ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=\
 * id.ocbc.chatty.baselineprofile.StageFrameBenchmark
 * ```
 *
 * `benchmarkRelease`, not `nonMinifiedRelease`. The latter exists so the Baseline Profile can be
 * generated against unshrunk code and would measure a build nobody ships; `benchmarkRelease` is the
 * release build with R8 applied and profiling left on, which is as close to the customer's APK as a
 * measurement can get.
 *
 * The numbers land in the test output and in a JSON file under
 * `baselineprofile/build/outputs/connected_android_test_additional_output/`. `frameDurationCpuMs`
 * P50 is the typical frame; P99 is the stutter a customer actually notices. Compare runs, not
 * absolutes — the device, its thermal state and the network all move the baseline.
 */
@RunWith(AndroidJUnit4::class)
class StageFrameBenchmark {

    @get:Rule val rule = MacrobenchmarkRule()

    @Test
    fun answerArrivingOnStage() = rule.measureRepeated(
        packageName = PACKAGE_NAME,
        metrics = listOf(FrameTimingMetric()),
        iterations = ITERATIONS,
        // What ships: R8-shrunk code with the Baseline Profile installed. `Require` fails loudly
        // rather than quietly measuring an interpreted build and reporting it as the product.
        compilationMode = CompilationMode.Partial(baselineProfileMode = BaselineProfileMode.Require),
        // The process is killed between iterations so each one opens its own session. Without it the
        // second iteration would measure a warm stage with a conversation already in it, which is a
        // different — and much cheaper — thing than the one being investigated.
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            device.reachStage()
        },
    ) {
        // Ask without the microphone. A starter chip is a fixed question with no keyboard, no speech
        // recogniser and no permission prompt in the way of the thing being measured.
        device.tapByTextOrDescription(TEXT_MODE)
        device.tapByTextOrDescription(STARTER)
        device.tapByTextOrDescription(SHOW_FACE)

        // Hold the window open while the answer streams and is spoken. Deliberately a wall-clock
        // wait and not `waitForIdle`: the stage animates continuously — level bars, a blinking caret
        // — so it is never idle, and waiting for that would hang until the timeout every time.
        SystemClock.sleep(OBSERVATION_MS)
    }

    /**
     * Gets from wherever the app opened to the stage, with a conversation running.
     *
     * The app restores its last conversation, so a cold start lands on the picker *or* straight back
     * on the stage depending on what happened last. Both are handled rather than assumed, because a
     * benchmark that only works on a freshly installed app is one nobody runs twice.
     */
    private fun UiDevice.reachStage() {
        // `Until.hasObject` yields a Boolean, so this is a plain true/false — checking it for null
        // would be true even when nothing matched, and the benchmark would sail on and tap a
        // customer card that is not on screen.
        if (onStage(UI_TIMEOUT_MS)) return
        tapByTextOrDescription(CUSTOMER)
        // The provider handshake is about 4.7 s, measured. The stage is up long before that — the
        // still stands in for the face — so this waits for the controls, not for the video.
        check(onStage(SESSION_TIMEOUT_MS)) { "the stage never opened" }
    }

    /**
     * Taps the first thing matching [candidates] by label or by accessibility description.
     *
     * Compose publishes a `contentDescription` as the node's description and visible text as its
     * text, and which one a given control carries is an implementation detail this test has no
     * business encoding. Both are tried, in both languages the app ships, so the benchmark does not
     * silently depend on the handset's locale.
     */
    private fun UiDevice.tapByTextOrDescription(candidates: List<String>) {
        for (label in candidates) {
            val byText = By.text(label)
            if (wait(Until.hasObject(byText), UI_TIMEOUT_MS)) {
                findObject(byText).click()
                return
            }
            val byDescription = By.descContains(label)
            if (hasObject(byDescription)) {
                findObject(byDescription).click()
                return
            }
        }
        error("none of $candidates is on screen")
    }

    /** Whether the stage's own controls are up, which is what "in a conversation" looks like. */
    private fun UiDevice.onStage(timeoutMs: Long): Boolean =
        wait(Until.hasObject(By.descContains(END_CALL)), timeoutMs)

    private companion object {
        /** The applicationId from `:app`. Keep in step with `app/build.gradle.kts`. */
        const val PACKAGE_NAME = "id.ocbc.sol"

        /** Each one bills a session. Three is enough for a median worth comparing. */
        const val ITERATIONS = 3

        /**
         * How long to watch the stage once the question is in.
         *
         * Long enough to cover the model's first token (~3.8 s, measured) and several spoken
         * sentences after it, which is where the streaming cost lives. Shorter and the window is
         * mostly the wait; much longer and every run pays for frames that all look the same.
         */
        const val OBSERVATION_MS = 20_000L

        const val UI_TIMEOUT_MS = 5_000L
        const val SESSION_TIMEOUT_MS = 15_000L

        // Both languages, because the app follows the handset and the benchmark should not care.
        val CUSTOMER = listOf("Michael")
        val TEXT_MODE = listOf("Mode teks", "Text mode")
        val SHOW_FACE = listOf("Tampilkan wajah", "Show the face")
        val STARTER = listOf("Cek saldo", "Check my balance")
        /**
         * The stop control's accessibility description, matched as a prefix.
         *
         * `descContains` rather than an exact match because the description is the longer hint —
         * "Akhiri percakapan" — while the visible label beneath it is just "Akhiri". Matching the
         * stem catches both, and both languages, without pinning this to either string.
         */
        const val END_CALL = "Akhiri"
    }
}
