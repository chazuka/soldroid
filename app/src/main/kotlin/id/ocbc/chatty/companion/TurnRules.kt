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
     * Whether a transcript is the agent's own voice coming back through the microphone.
     *
     * # Why this is needed at all
     *
     * Handsfree is half-duplex: the microphone is shut for the whole of a turn and re-armed at the
     * end of it. The end is taken from the provider reporting `agent.speak_ended` — but that is the
     * provider saying *it* has finished, while the audio is still crossing the network and sitting
     * in a jitter buffer on its way to the speaker. So the microphone can open while the last
     * seconds of the answer are still playing out loud, hear them, and ask the agent about itself.
     *
     * A longer pause before re-arming makes this rarer and cannot make it impossible, because the
     * lag is a network's to decide, not ours. So the words are checked too: a question that is a
     * verbatim run out of the answer the agent just gave is the room, not the customer.
     *
     * # Why verbatim, and why a run rather than the whole thing
     *
     * A person paraphrases; a microphone does not. Echoes come back as exact stretches of what was
     * said, so several consecutive words matching is strong evidence and one or two is nothing —
     * "berapa saldo saya" could easily appear inside an answer about a balance, and a customer is
     * entitled to ask it. [ECHO_RUN_WORDS] is where that line sits.
     *
     * ```
     * // answer: "Total sekitar Rp400.800.000 tersebar di rekening gaji dan deposito."
     * isEcho("tersebar di rekening gaji dan", answer)   // true  — five words, verbatim
     * isEcho("berapa saldo saya", answer)               // false — the customer asking
     * ```
     */
    fun isEcho(question: String, lastAnswer: String?): Boolean {
        if (lastAnswer.isNullOrBlank()) return false
        val spoken = question.words()
        if (spoken.size < ECHO_RUN_WORDS) return false
        val answer = lastAnswer.words()
        if (answer.size < ECHO_RUN_WORDS) return false
        return spoken.windowed(ECHO_RUN_WORDS).any { run ->
            answer.windowed(ECHO_RUN_WORDS).any { it == run }
        }
    }

    /**
     * How many consecutive words must match before a question is treated as the agent's own voice.
     *
     * Five is long enough that a customer's own phrasing is unlikely to land on it by accident, and
     * short enough to catch the tail of a sentence, which is the part that actually leaks — the
     * microphone opens near the end of the answer, not the start of it.
     */
    private const val ECHO_RUN_WORDS = 5

    /** The words of a transcript, lower case, with everything that is not a word discarded. */
    private fun String.words(): List<String> =
        lowercase().map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }

    /**
     * Whether two transcripts are the same question, allowing for how a recogniser tidies up.
     *
     * # Why this decides whether a speculative answer may be used
     *
     * A question can be sent to the model before the recogniser has finished, using the partial
     * transcript, which buys back most of the pause spent waiting for silence. That is only safe if
     * the answer is thrown away unless the finished transcript says the same thing — otherwise the
     * customer gets an answer to half a sentence, which is worse than waiting for the whole one.
     *
     * Exact equality would throw away almost every speculation, because finalising is precisely
     * when a recogniser adds the full stop, fixes the capital and settles on a spelling. So the
     * comparison is on the words: case folded, punctuation dropped, runs of space collapsed.
     * Anything that changes a *word* is a different question and the speculation is discarded.
     *
     * ```
     * sameQuestion("berapa saldo saya", "Berapa saldo saya?")   // true  — tidied, not changed
     * sameQuestion("berapa saldo", "berapa saldo saya")          // false — they said more
     * ```
     */
    fun sameQuestion(spoken: String, finished: String): Boolean =
        spoken.asQuestionKey() == finished.asQuestionKey()

    /**
     * The comparable form of a transcript: its words, and nothing a recogniser adds on the way out.
     *
     * Digits are kept as digits and letters folded to lower case. Everything that is neither is a
     * separator, which collapses "Rp3.240.000?" and "rp3 240 000" to the same key — right for this
     * purpose, because a recogniser that re-punctuates a figure has not heard a different question.
     */
    private fun String.asQuestionKey(): String =
        lowercase().map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")

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
