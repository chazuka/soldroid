package id.ocbc.chatty.baselineprofile

import android.os.SystemClock
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
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
        // Deliberately no `startupMode`.
        //
        // `StartupMode.COLD` kills the process *after* `setupBlock` and before `measureBlock`, which
        // is right for a startup benchmark and wrong for this one: it threw away the navigation this
        // benchmark does in setup and handed the measure block a dead app. The symptom was a tap for
        // the text-mode control failing on a screen that, moments earlier, had been the stage.
        //
        // The kill is done here instead, at the top of setup, so each iteration still opens its own
        // session — without it the second iteration would measure a warm stage with a conversation
        // already in it — while the navigation that follows stays outside the measurement.
        setupBlock = {
            killProcess()
            // Granted before the app is ever launched, because on a fresh install the first
            // conversation raises the system's notification dialog and parks it over the stage.
            // UI Automator then taps a card that is behind a modal window and nothing happens —
            // which is exactly how the first two runs of this benchmark failed, reporting "the stage
            // never opened" and then "none of [Michael] is on screen" as the retry tapped the
            // dialog.
            //
            // A returning customer has answered this once and never sees it again, so granting it is
            // also the more representative state to measure.
            GRANTS.forEach { device.executeShellCommand("pm grant $PACKAGE_NAME $it") }
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

        // Tapped more than once on purpose.
        //
        // A customer's name is drawn from a bundled string and is on screen the instant the list is,
        // but the row it belongs to cannot open a conversation until the agent roster has arrived
        // over the network — the card is a customer joined to an agent, and half of that join is a
        // fetch. The first run of this benchmark tapped as soon as it saw the name, landed on a row
        // that was not live yet, and then waited fifteen seconds for a stage nobody had opened.
        //
        // Retrying is the honest fix. Waiting for the roster would mean the benchmark asserting on a
        // network call's timing, which is precisely the thing it must not measure; tapping again
        // costs nothing once the row is live, because by then the stage is already up and the loop
        // has exited.
        repeat(OPEN_ATTEMPTS) {
            tapByTextOrDescription(CUSTOMER)
            // The provider handshake is about 4.7 s, measured. The stage is up long before that —
            // the still stands in for the face — so this waits for the controls, not for the video.
            if (onStage(SESSION_TIMEOUT_MS)) return
        }
        error("the stage never opened after $OPEN_ATTEMPTS attempts")
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
        // Description first, and that order is load-bearing.
        //
        // A stage control is an icon button with the description on it and a caption drawn *below*
        // it, outside its touch target — two nodes carrying the same words. Matching on text found
        // the caption, clicked its centre, and hit nothing; the screen never changed and the next
        // step failed looking for something that was never going to appear. Customer cards are the
        // other way round: they carry text and no description, so they fall through to the second
        // branch and still work.
        for (label in candidates) {
            val byDescription = By.descContains(label)
            if (wait(Until.hasObject(byDescription), UI_TIMEOUT_MS)) {
                findObject(byDescription).click()
                return
            }
            val byText = By.text(label)
            if (hasObject(byText)) {
                findObject(byText).click()
                return
            }
        }
        error("none of $candidates is on screen")
    }

    /**
     * Whether the stage's own controls are up, which is what "in a conversation" looks like.
     *
     * Keyed on the text-mode control rather than the stop button, because it is the very next thing
     * this benchmark taps: if it can be seen it can be used, and there is no window where the check
     * passes but the tap that follows finds nothing.
     */
    private fun UiDevice.onStage(timeoutMs: Long): Boolean =
        TEXT_MODE.any { wait(Until.hasObject(By.descContains(it)), timeoutMs / TEXT_MODE.size) }

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
         * Permissions the conversation asks for, granted ahead of the run.
         *
         * The microphone is in the list even though this journey types rather than speaks: the stage
         * may ask for it on entry, and a dialog appearing halfway through an observation window
         * would be measured as the app's own frames.
         */
        val GRANTS = listOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECORD_AUDIO",
        )

        /**
         * How many times to try opening a conversation before giving up.
         *
         * Each attempt already waits out a session handshake, so this is minutes of patience, not
         * seconds — enough for a slow roster fetch on a bad connection and no more.
         */
        const val OPEN_ATTEMPTS = 4
    }
}
