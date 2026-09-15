package id.ocbc.chatty.companion

/**
 * The rules handsfree runs on, as plain functions.
 *
 * # Why these are not in the composable
 *
 * They were, and they broke twice in two days — both times in error handling, both times invisible
 * on the happy path, and both times the symptom was the same: handsfree left switched on and deaf,
 * the button saying it was listening while nothing was. A state machine that can end up lying about
 * itself needs tests, and logic tangled into a `LaunchedEffect` cannot have any.
 *
 * Nothing here touches Compose, Android or a clock. Every decision is a function of values the
 * caller already has, so the screen is left doing what a screen should — reading state, calling
 * these, and acting — and each rule can be stated as a test rather than as a comment.
 */
object Handsfree {

    /** What should happen to the microphone right now. */
    sealed interface Intent {
        /** Open it, after [delayMs]. */
        data class Listen(val delayMs: Long) : Intent

        /** Close it and leave it closed. */
        data object Close : Intent

        /** Switch handsfree off and tell the customer why. */
        data object GiveUp : Intent
    }

    /**
     * Whether to open the microphone, close it, or end the mode.
     *
     * [quietMs] is how long since a question was last heard, measured only while listening was
     * possible — a customer reading an answer in the thread has not gone quiet on the agent.
     *
     * ```
     * Handsfree.intent(on = true, onStage = true, accepting = true, quietMs = 0, retryDelayMs = 0)
     * // Listen(delayMs = 400)
     * ```
     */
    fun intent(
        on: Boolean,
        onStage: Boolean,
        accepting: Boolean,
        quietMs: Long,
        retryDelayMs: Long,
    ): Intent = when {
        // The agent speaks through the same handset the microphone is in, so the microphone is shut
        // for the whole of a turn rather than merely not re-opened.
        !on || !onStage || !accepting -> Intent.Close
        quietMs > QUIET_MS -> Intent.GiveUp
        else -> Intent.Listen(REARM_MS + retryDelayMs)
    }

    /** What to do about a failed listen. */
    data class Recovery(
        /** The problem worth showing the customer, or null to say nothing. */
        val notify: SpeechProblem?,
        /** Whether to open the microphone again. */
        val rearm: Boolean,
        /** Extra pause before that, so a failing recogniser is retried rather than hammered. */
        val backoffMs: Long,
    )

    /**
     * How a failed listen should be handled.
     *
     * # The rule, stated the way round that survives
     *
     * Keep going unless waiting cannot help. The first version of this named the problems worth
     * retrying and got the list wrong twice — an outage produced `NO_NETWORK` and then `FAILED`,
     * neither of which was on it, so the loop died while the mode still claimed to be listening.
     * Permission and a missing recogniser are the only things a retry cannot fix.
     *
     * A silent room says nothing, because in handsfree the microphone is open because the app opened
     * it, and reporting "not caught" for speech nobody made is noise. Everything else is reported
     * once and retried after a pause.
     */
    fun recover(problem: SpeechProblem, on: Boolean): Recovery {
        val permanent = problem == SpeechProblem.PERMISSION_DENIED ||
            problem == SpeechProblem.PERMISSION_JUST_GRANTED ||
            problem == SpeechProblem.UNAVAILABLE

        if (!on || permanent) return Recovery(notify = problem, rearm = false, backoffMs = 0L)

        val silence = problem == SpeechProblem.NO_MATCH
        return Recovery(
            notify = if (silence) null else problem,
            rearm = true,
            backoffMs = if (silence) 0L else FAULT_BACKOFF_MS,
        )
    }

    /**
     * What a failure actually was, given what the platform knows and the recogniser does not.
     *
     * Offline, Google's service reports `ERROR_NO_MATCH` — the same code it uses for a silent room —
     * so the copy blamed the customer's speech for a dead connection and invited them to repeat
     * themselves into a microphone that could not work either way.
     */
    fun classify(problem: SpeechProblem, hasNetwork: Boolean): SpeechProblem =
        if (problem == SpeechProblem.NO_MATCH && !hasNetwork) SpeechProblem.NO_NETWORK else problem

    /** What letting go of the microphone means. */
    enum class Release {
        /** Transcribe what was heard. */
        SUBMIT,

        /** Throw it away: too short to be speech, so it is a mis-tap or half a double tap. */
        DISCARD,

        /** Nothing — handsfree ends its own listens. */
        IGNORE,
    }

    /**
     * Whether a released press should submit, be discarded, or be ignored.
     *
     * A press under [MIN_HOLD_MS] captured nothing worth sending: it is a stray tap on a large round
     * button, or the first half of the double tap that toggles the mode. Submitting it earned the
     * customer a "not caught" message for something they never said.
     */
    fun release(on: Boolean, heldMs: Long): Release = when {
        on -> Release.IGNORE
        heldMs < MIN_HOLD_MS -> Release.DISCARD
        else -> Release.SUBMIT
    }

    /** A beat after the avatar stops, so the microphone does not catch the tail of the last word. */
    const val REARM_MS = 400L

    /** How long the room may stay quiet before the mode switches itself off. */
    const val QUIET_MS = 180_000L

    /** How much longer to wait before re-arming after a fault rather than a silent room. */
    const val FAULT_BACKOFF_MS = 2_500L

    /** The shortest press that counts as hold-to-talk. */
    const val MIN_HOLD_MS = 400L
}
