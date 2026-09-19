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

        /** Close it and leave it closed. [because] is for the log, never for the customer. */
        data class Close(val because: String) : Intent

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
        avatarSpeaking: Boolean,
        reconnecting: Boolean,
        avatarOpening: Boolean,
        quietMs: Long,
        retryDelayMs: Long,
    ): Intent = when {
        // The agent speaks through the same handset the microphone is in, so the microphone is shut
        // for the whole of a turn rather than merely not re-opened.
        //
        // [avatarSpeaking] is the belt to the turn's braces. A turn ends when the provider says the
        // utterance is over, and that has been observed arriving while the avatar is plainly still
        // talking; this comes from the decoded audio instead, so it cannot be early. Without it the
        // microphone opened into the tail of an answer and the agent started interviewing itself.
        // [reconnecting] closes it for a different reason than the rest: nothing said into a room
        // that has dropped can reach anyone, so listening is only a way to collect a question that
        // will be answered by a face which is currently a still image.
        // [avatarOpening] closes it for a third reason, and this one is about the microphone rather
        // than the answer. Standing up a provider session negotiates a WebRTC connection, and on a
        // handset that contends with whoever is holding the audio input: a listen that began 670ms
        // into a 1,911ms session open died 56ms later with ERROR_CLIENT, which then costs a recovery
        // backoff on top. Waiting out the open is cheaper than the failure it avoids.
        // Each reason is named rather than collapsed into one branch. "Handsfree is on and the
        // microphone is not listening" has been reported four separate times, each a different
        // cause, and every time the first question was which one — a log that says only "closed"
        // cannot answer it.
        !on -> Intent.Close("handsfree is off")
        !onStage -> Intent.Close("not on the stage")
        !accepting -> Intent.Close("a turn is in flight")
        avatarSpeaking -> Intent.Close("the avatar is still audible")
        reconnecting -> Intent.Close("the room is reconnecting")
        avatarOpening -> Intent.Close("the avatar session is being opened")
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

    /**
     * How long to wait after the avatar stops before opening the microphone again.
     *
     * # Why this is longer than it looks like it needs to be
     *
     * "The avatar stopped" is the *provider* saying it has finished sending, and at that moment the
     * last seconds of audio are still crossing the network and sitting in a jitter buffer on their
     * way to the speaker. Re-arming on that signal opened the microphone into the tail of the
     * answer: it transcribed the agent and asked the agent about itself, in front of whoever was
     * watching.
     *
     * A longer wait makes that rarer. It cannot make it impossible — how far behind the speaker is
     * depends on a network, not on this constant — which is why the words are checked as well; see
     * [TurnRules.isEcho]. This is the cheap half of the fix and the guard is the reliable half.
     *
     * The cost is how quickly the microphone comes back between questions, so it is kept as short
     * as it can be while covering an ordinary buffer.
     */
    const val REARM_MS = 1_200L

    /** How long the room may stay quiet before the mode switches itself off. */
    const val QUIET_MS = 180_000L

    /**
     * How much longer to wait before re-arming after a fault rather than a silent room.
     *
     * Long enough not to hammer a recogniser that is genuinely unwell, short enough that a
     * transient one does not read as the app going deaf. `ERROR_CLIENT` is the fault this actually
     * sees and it is transient — the next listen usually succeeds — so 2.5s on top of
     * [REARM_MS] was nearly four seconds of a microphone that looked open and heard nothing, which
     * is the complaint this mode attracts.
     */
    const val FAULT_BACKOFF_MS = 800L

    /** The shortest press that counts as hold-to-talk. */
    const val MIN_HOLD_MS = 400L
}
