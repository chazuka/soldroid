package id.ocbc.chatty.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Regroups a stream of model fragments into whole sentences.
 *
 * # Why this exists
 *
 * The chat API emits a few characters at a time; the synthesizer needs a clause it can give
 * intonation to. Feeding it fragments would produce audio that lurches, and waiting for the whole
 * answer would put the model's full generation time in front of the first sound the customer hears.
 * A sentence is the smallest unit that sounds like speech, so it is the unit the pipeline runs on.
 *
 * # Why the naive split is wrong here
 *
 * These agents talk about money, in Indonesian, where the thousands separator is a full stop:
 * `Rp3.240.000` contains three of them. Splitting on "." alone would hand the synthesizer "Rp3." and
 * then "240." — three fragments and a mangled figure. A break is therefore only taken when the
 * punctuation is *followed by whitespace*, which a thousands separator never is.
 *
 * That rule needs one character of lookahead, so a sentence is never emitted until the fragment
 * after it has arrived. The final sentence has nothing after it and is flushed when the stream ends.
 *
 * ```
 * chat.reply("emma", history)
 *     .sentences()
 *     .collect { sentence -> session.speak(synthesizer.speak(voiceId, sentence)) }
 * ```
 *
 * # Why the first chunk is special
 *
 * Everything after the first clause is overlapped with speech that is already playing, so its size
 * costs nothing. The first chunk is different: the customer waits for it in silence, and it is the
 * only part of the pipeline where a shorter unit is strictly better.
 *
 * Measured on a real turn: the model's first token arrived at 3.6 s, but the first audio frame did
 * not reach the avatar until 8.0 s — because the opening sentence ran long and the splitter waited
 * for its full stop. Breaking the *first* chunk at a clause boundary as well (a dash, comma, colon
 * or semicolon) recovers most of that gap. Later chunks keep whole-sentence boundaries, because a
 * whole sentence is what a synthesiser needs to get intonation right and there is no longer any
 * latency to trade for it.
 *
 * @param minChars a sentence shorter than this is held back and joined to the next one. "Halo!" is
 *   a legitimate sentence and a wasteful synthesis request; batching the short ones costs nothing
 *   and saves a round trip per exclamation.
 * @param firstChunkMinChars the same floor for the opening clause, lower because this is the one
 *   the customer waits for.
 */
fun Flow<String>.sentences(
    minChars: Int = DEFAULT_MIN_SENTENCE_CHARS,
    firstChunkMinChars: Int = DEFAULT_FIRST_CHUNK_MIN_CHARS,
): Flow<String> = flow {
    val buffer = StringBuilder()
    var emittedAny = false

    collect { fragment ->
        buffer.append(fragment)
        while (true) {
            val floor = if (emittedAny) minChars else firstChunkMinChars
            val terminators = if (emittedAny) SENTENCE_TERMINATORS else FIRST_CHUNK_TERMINATORS
            val end = buffer.chunkEnd(floor, terminators)
                ?: buffer.figureBreak(floor).takeIf { !emittedAny && it != null }
                ?: break
            val sentence = buffer.substring(0, end).trim()
            buffer.delete(0, end)
            if (sentence.isNotEmpty()) {
                emit(sentence)
                emittedAny = true
            }
        }
    }

    val remainder = buffer.toString().trim()
    if (remainder.isNotEmpty()) emit(remainder)
}

/**
 * The index just past the first complete chunk in this buffer, or null while none is complete.
 *
 * Returns null rather than the last character's index when the terminator is at the very end: with
 * nothing after it there is no way to tell a full stop from a thousands separator — or a decimal
 * comma from a clause break — yet.
 */
private fun CharSequence.chunkEnd(minChars: Int, terminators: String): Int? {
    for (i in indices) {
        if (this[i] !in terminators) continue
        val next = getOrNull(i + 1) ?: return null
        if (!next.isWhitespace()) continue
        if (i + 1 < minChars) continue
        return i + 1
    }
    return null
}

/**
 * The index just past a completed figure, or null while none has finished.
 *
 * # Why the opening chunk breaks on a number and nothing else does
 *
 * Punctuation is what makes a chunk sayable, so everywhere else the splitter waits for it. The
 * opening chunk cannot afford to: measured on real answers, the first comma or dash lands between
 * 31 and 54 characters in, and every one of those is the customer sitting in silence watching a
 * face that has not moved. Lowering the *floor* does nothing about it — a floor only matters when
 * a boundary arrives early, and one never does.
 *
 * Breaking at any old word was the obvious alternative and it sounded wrong: it produced
 * "Saldo kamu Rp3.240.000 saat" followed by "ini.", splitting a fixed phrase down the middle, and
 * a fragment is read with the falling tone of a finished sentence. A figure is different. These
 * answers are instructed to lead with one, a speaker pauses after saying a number anyway, and it
 * is the one place a break lands where a person would have put one.
 *
 * The digits must be followed by whitespace, which is what keeps a figure whole: `Rp3.240.000` has
 * no space in it, so this can never hand the number speller half of one.
 */
private fun CharSequence.figureBreak(minChars: Int): Int? {
    for (i in indices) {
        if (!this[i].isDigit()) continue
        val next = getOrNull(i + 1) ?: return null
        if (!next.isWhitespace()) continue
        if (i + 1 < minChars) continue
        return i + 1
    }
    return null
}

private fun CharSequence.getOrNull(index: Int): Char? = if (index in indices) this[index] else null

private const val SENTENCE_TERMINATORS = ".!?…\n"

/**
 * What may end the *opening* chunk: a sentence ending, or a clause break.
 *
 * The comma is in here despite being Indonesian's decimal separator, and that is safe for the same
 * reason the full stop is: a break is only taken when whitespace follows, and "1,2%" has a digit
 * there. The dashes are the common case in these answers — "Rp3.240.000 — naik terus…" — and are
 * exactly where a speaker would draw breath.
 */
private const val FIRST_CHUNK_TERMINATORS = ".!?…\n,;:—–"

/** Long enough that "Halo!" joins the clause after it, short enough that a real sentence never does. */
private const val DEFAULT_MIN_SENTENCE_CHARS = 24

/**
 * The floor for the opening chunk. Lower than [DEFAULT_MIN_SENTENCE_CHARS] because this is the one
 * the customer waits for in silence, but not so low that the avatar opens with two words.
 */
private const val DEFAULT_FIRST_CHUNK_MIN_CHARS = 18


