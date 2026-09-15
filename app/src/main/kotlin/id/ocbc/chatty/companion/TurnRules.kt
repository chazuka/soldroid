package id.ocbc.chatty.companion

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
