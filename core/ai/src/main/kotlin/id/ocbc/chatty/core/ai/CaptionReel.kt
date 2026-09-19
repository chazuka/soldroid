package id.ocbc.chatty.core.ai

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay

/**
 * Lets an answer onto the screen at the speed it is being spoken.
 *
 * # Why this is not a text stream
 *
 * The model finishes writing long before the avatar finishes speaking — measured on this app, the
 * text was complete at 6.0s while the voice was still going at 41.5s. Shown as it arrives, the words
 * have nothing to do with the mouth above them. The audio is its own clock: it is PCM at a known
 * rate, so each clause's byte count *is* its duration, and revealing a clause only after the ones
 * before it have had time to play keeps the caption in step with the voice.
 *
 * ```
 * launch { revealCaptions(clauses, awaitStart = { speakStarted.await() }) { text -> show(text) } }
 * ```
 */
suspend fun revealCaptions(
    clauses: ReceiveChannel<SpokenCaption>,
    awaitStart: suspend () -> Unit,
    onCaption: (String) -> Unit,
) {
    // Nothing to be in step with until the provider says the lips have moved, and a caption that
    // appears before the voice is the same desync in the other direction.
    awaitStart()

    var shown = ""
    for (clause in clauses) {
        // A clause arrives whole but is not said whole. Appearing all at once made the caption
        // jump ahead of the voice by the length of a sentence and then wait for it to catch up —
        // the words were in step on average and visibly out of step most of the time.
        //
        // The audio's own length is known, so the words are spread across it: each appears roughly
        // when it is spoken, and the clause still takes exactly as long to reveal as it takes to
        // say. Drift cannot accumulate, because every clause ends where it would have ended.
        val words = clause.text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) continue
        val perWord = clause.durationMs / words.size
        words.forEach { word ->
            shown = join(shown, word)
            onCaption(shown)
            delay(perWord)
        }
    }
}

/**
 * Puts two clauses back together with the space the splitter removed.
 *
 * [sentences] breaks where whitespace follows and keeps neither side of it, which is invisible to a
 * synthesizer and obvious on screen: concatenated raw, the pieces read "Anda,membeli".
 *
 * ```
 * join("Berdasarkan angka Anda,", "membeli mobil")   // "Berdasarkan angka Anda, membeli mobil"
 * ```
 */
internal fun join(soFar: String, clause: String): String {
    val needsGap = soFar.isNotEmpty() && !soFar.endsWith(' ') && !clause.startsWith(' ')
    return if (needsGap) "$soFar $clause" else soFar + clause
}
