package id.ocbc.chatty.core.ai

/**
 * Where the time went in one turn.
 *
 * # Why measure this
 *
 * The customer experiences a turn as one number — how long from letting go of the microphone to the
 * avatar's lips moving — but that number is the sum of three vendors, and they fail to be fast in
 * different ways. Without the split, tuning is guesswork: a slow turn looks identical whether the
 * model was thinking, the synthesizer was queued, or the renderer was starved.
 *
 * Every field is milliseconds since the question was asked, and null until that moment happens.
 * [TurnTrace] itself only holds the numbers; a caller fills them in with `copy`, typically through a
 * small per-turn helper that reads elapsed time off a clock and only sets a field the first time:
 *
 * ```
 * var trace = TurnTrace(askedAtMs = clock.now())
 * fun mark(update: TurnTrace.() -> TurnTrace) { trace = trace.update() }
 *
 * mark { copy(firstTokenMs = firstTokenMs ?: (clock.now() - askedAtMs)) }
 * ```
 */
data class TurnTrace(
    /** Wall-clock start, so the marks below can be relative and therefore readable. */
    val askedAtMs: Long,

    /**
     * How long the recogniser took between the customer stopping and the question arriving.
     *
     * The only leg that happens *before* [askedAtMs], which is why it is a duration rather than a
     * mark — and why it went unmeasured for so long. It is also the leg the customer feels most
     * sharply in handsfree, where nobody let go of a button: a fixed silence has to elapse before
     * the recogniser will call the sentence finished, and only then does any of the work below
     * start. Null when the question was typed, which has no listening to do.
     */
    val listenedMs: Long? = null,

    /** The model's first fragment. This is the LLM's latency, and usually the largest single term. */
    val firstTokenMs: Long? = null,

    /** The model stopped generating. */
    val answerCompleteMs: Long? = null,

    /** The first PCM frame reached the avatar — i.e. the synthesizer's first byte, plus our overhead. */
    val firstAudioMs: Long? = null,

    /** The provider reported `agent.speak_started`: the lips moved. This is what the customer felt. */
    val speakStartedMs: Long? = null,

    /** The provider reported `agent.speak_ended`. */
    val speakEndedMs: Long? = null,

    /** How many sentences the answer was split into, and therefore how many synthesis calls it cost. */
    val sentences: Int = 0,

    /**
     * How many times the provider ran out of audio mid-utterance.
     *
     * The lip-sync failure that a customer actually notices. LiveAvatar drives the mouth from the
     * samples it has been sent, so when the supply arrives slower than it is played the face stalls
     * mid-word and then jumps to catch up. Counting it is the difference between "the sync feels
     * off" and a number that moves when the pipeline changes.
     */
    val starved: Int = 0,
) {
    /** True once the avatar has finished; a trace stops changing here. */
    val complete: Boolean get() = speakEndedMs != null

    /**
     * How long synthesis took to produce something sayable, once the model had given it words.
     *
     * Derived rather than marked, because there is no separate clock to start: the synthesizer is
     * handed the first clause the moment one is complete, so the gap between the model's first
     * token and the first audio frame *is* the synthesis leg plus this app's own clause splitting.
     *
     * Null until both ends exist. A turn that failed before any audio has no synthesis leg, and
     * reporting zero would put it in the same bucket as an instant one.
     */
    val ttsMs: Long? get() = both(firstTokenMs, firstAudioMs)

    /**
     * How long the provider took to turn audio into a moving mouth.
     *
     * The leg this app does not control and, on the evidence, the one that varies most. Measured on
     * a Galaxy S25: 0.86s and 0.95s on turns answered by [Brain.ANTHROPIC], against 4.4s and 6.4s on
     * turns answered by [Brain.KAMARTAJ] in the same sitting. Why a provider that is handed audio
     * the same way either time should take five times longer is not something this app can see from
     * the outside, which is the reason to keep measuring it. It is derived for the
     * same reason [ttsMs] is — the audio is handed over continuously, so the gap between the first
     * frame sent and the provider reporting lips is the whole of its render latency.
     */
    val avatarMs: Long? get() = both(firstAudioMs, speakStartedMs)

    /** The span between two marks, or null unless both happened in that order. */
    private fun both(from: Long?, to: Long?): Long? =
        if (from != null && to != null && to >= from) to - from else null

    /** One line for a log or a debug overlay. Null marks render as `—`, never as `0`. */
    fun summary(): String = buildString {
        listenedMs?.let { append("heard ").append(it.ms()).append("  ") }
        append("llm ").append(firstTokenMs.ms()).append('/').append(answerCompleteMs.ms())
        append("  tts ").append(firstAudioMs.ms())
        append("  lips ").append(speakStartedMs.ms()).append('/').append(speakEndedMs.ms())
        append("  ").append(sentences).append(" sentence(s)")
        if (starved > 0) append("  starved ").append(starved).append('x')
    }

    private fun Long?.ms(): String = this?.let { "${it}ms" } ?: "—"
}
