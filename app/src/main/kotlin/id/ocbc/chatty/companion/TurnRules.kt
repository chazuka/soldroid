package id.ocbc.chatty.companion

import id.ocbc.chatty.core.ai.telemetry.TurnOutcome

/**
 * How a turn ends, as plain functions.
 *
 * # Why these are not inline
 *
 * Three of this app's shipped bugs lived in the few lines that close a turn, and all three were
 * invisible on the happy path: the caption was cut off before it finished revealing, the screen could
 * stick on "Berbicara" forever with the microphone disabled, and a retry was offered for answers that
 * had already arrived. None of it was testable while it was tangled into `runTurn`.
 *
 * Nothing here touches Android, coroutines or a clock.
 */
object TurnRules {

    /**
     * How this turn should be reported to whoever is collecting turns.
     *
     * # Why this is a rule rather than three lines at each call site
     *
     * Because there are two call sites — the turn that worked and the turn that did not — and the
     * interesting cases live between them. An answer that arrived but was never spoken is a success
     * to the customer reading it and a failure of the voice; a turn the customer cut short is not a
     * failure at all, though it unwinds down the same path as one. Getting that wrong does not break
     * anything, which is exactly why it would never be noticed: it quietly reports a pipeline that
     * fails more, or less, than it really does.
     *
     * [interrupted] wins over everything. Barge-in ends a turn early by design, and whatever the
     * avatar did or did not manage afterwards is not a fault worth counting.
     *
     * ```
     * TurnRules.outcome(answered = true, spoke = false, interrupted = false, network = false)
     * // ANSWERED_IN_TEXT — the customer got their answer, the face just never said it
     * ```
     */
    fun outcome(
        answered: Boolean,
        spoke: Boolean,
        interrupted: Boolean,
        network: Boolean,
    ): TurnOutcome = when {
        interrupted -> TurnOutcome.INTERRUPTED
        answered && spoke -> TurnOutcome.SPOKEN
        answered -> TurnOutcome.ANSWERED_IN_TEXT
        network -> TurnOutcome.FAILED_NETWORK
        else -> TurnOutcome.FAILED_API
    }

    /** What should happen to the caption that is still catching up with the voice. */
    enum class Captions {
        /**
         * Let it finish on its own.
         *
         * The reveal is deliberately slower than the transfer, so when the provider reports the lips
         * have stopped it is still a clause or more behind. Cutting it off there froze the answer
         * mid-sentence and the rest was never shown.
         */
        DRAIN,

        /**
         * Stop where the voice stopped.
         *
         * Barge-in: the customer cut the answer off, and showing them the words they just silenced is
         * the opposite of what pressing stop meant.
         */
        FREEZE,

        /**
         * Show all of it at once.
         *
         * The rest is never going to be spoken — the voice failed, or there was no avatar — so pacing
         * it against audio that does not exist would leave the screen frozen mid-answer.
         */
        SHOW_ALL,
    }

    /** Everything the end of a turn decides. */
    data class Ending(
        val captions: Captions,
        /** Whether to offer the question again. */
        val retryable: Boolean,
        /** Whether the provider session is spent and a fresh one is needed. */
        val replaceSession: Boolean,
    )

    /**
     * How to close a turn.
     *
     * [answered] is whether any text came back at all; [spoke] whether the voice got through it;
     * [interrupted] whether the customer cut it short.
     *
     * ```
     * TurnRules.ending(answered = true, spoke = true, interrupted = false, hadSession = true)
     * // Ending(DRAIN, retryable = false, replaceSession = false)
     * ```
     */
    fun ending(answered: Boolean, spoke: Boolean, interrupted: Boolean, hadSession: Boolean): Ending =
        when {
            interrupted -> Ending(Captions.FREEZE, retryable = false, replaceSession = false)

            spoke -> Ending(Captions.DRAIN, retryable = false, replaceSession = false)

            // The voice failed. Offer the question again only when nothing came back: if the answer
            // arrived and only the speech did not, the customer already has what they asked for, and
            // a retry button there invites them to pay for the same answer twice.
            else -> Ending(
                captions = Captions.SHOW_ALL,
                retryable = !answered,
                // A speak that failed took the socket with it.
                replaceSession = hadSession,
            )
        }

    /**
     * Whether a provider event may move this app's phase.
     *
     * The provider's speak events are not only ours. One arriving between turns used to leave the
     * screen claiming the agent was still talking — which disables the microphone and never clears,
     * so the conversation looks hung while nothing is wrong. Phase is this app's state machine, and
     * only a turn this app started may move it.
     */
    fun phaseMayMove(turnInFlight: Boolean): Boolean = turnInFlight
}
