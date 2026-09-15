package id.ocbc.chatty.core.ai

/**
 * One clause of an answer, paired with how long it takes to say.
 *
 * # Why the answer needs timing at all
 *
 * The model finishes writing long before the avatar finishes speaking — measured on this app, the
 * text was complete at 6.0s and the voice was still going at 41.5s. Showing the finished text next
 * to a face that is thirty seconds behind it is the desync a customer actually sees: the words on
 * screen have nothing to do with the mouth above them.
 *
 * The audio itself is the clock. It is PCM at a known rate, so its byte count *is* its duration —
 * no alignment data, no provider support, nothing to estimate. Reveal each clause after the ones
 * before it have had time to play and the caption tracks the voice.
 *
 * ```
 * val clause = SpokenCaption(text = "Saldo kamu tiga juta rupiah.", audioBytes = 96_000)
 * clause.durationMs   // 2000
 * ```
 */
data class SpokenCaption(val text: String, val audioBytes: Long) {
    /**
     * How long this clause's audio lasts.
     *
     * Derived from the one format LiveAvatar LITE accepts — signed 16-bit mono at 24 kHz, so
     * 48,000 bytes to the second. A format change here would silently drift the captions, which is
     * why the arithmetic names its terms instead of hiding a constant.
     */
    val durationMs: Long get() = audioBytes * MILLIS_PER_SECOND / BYTES_PER_SECOND

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val BYTES_PER_SECOND = 24_000L * 2
    }
}
