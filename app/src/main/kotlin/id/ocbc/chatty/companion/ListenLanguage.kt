package id.ocbc.chatty.companion

import id.ocbc.chatty.core.ai.Language

/**
 * When to re-point the speech recogniser at the other language.
 *
 * # Why this is not simply "whatever they just said"
 *
 * Answering in the wrong language and listening in the wrong language are not the same mistake. The
 * first is a wrong voice reading the right words: ugly, obvious, and undone by the next turn. The
 * second destroys the words. Pinned to `id-ID`, Google's recogniser does not fail on an English
 * question, it transliterates it — "How much money do I have" arrives as "Oh macam mana" — and no
 * amount of care downstream can recover a sentence that was never transcribed.
 *
 * So the answer's language may chase every utterance, and the recogniser's may not. One English
 * sentence inside an Indonesian conversation is ordinary: a product name, a figure read aloud, a
 * thought that happened to arrive in English. Re-pointing the ear at it would mangle the Indonesian
 * that follows, which trades one failure for the same failure in the other direction.
 *
 * Two in a row is different. That is a customer who has changed language, not one who borrowed a
 * phrase, and the hint should follow them.
 *
 * # What covers the gap in the meantime
 *
 * The platform's own bilingual switching, which is the mechanism actually doing this work: from
 * Android 13 the recogniser may change language mid-utterance, and from 14 it can be restricted to
 * this app's two languages. This rule exists so the app's hint stops fighting it, not to replace it.
 * Below Android 13 there is no switching and the header's control is the only way, which is why it
 * stays on screen.
 *
 * ```
 * // Asking one English question in an Indonesian conversation does not move the ear.
 * val first = ListenLanguage.next(Language.INDONESIAN, Language.ENGLISH, streak = 0)
 * // first.language == INDONESIAN, first.streak == 1
 *
 * // Asking a second one does.
 * val second = ListenLanguage.next(Language.INDONESIAN, Language.ENGLISH, first.streak)
 * // second.language == ENGLISH, second.streak == 0
 * ```
 */
internal object ListenLanguage {

    /**
     * The language to listen for next, and the streak to carry into the question after it.
     *
     * [spoken] is null when the question was too short to read — a figure, a "ya". Nothing was
     * learned, so nothing moves and the streak is left exactly where it was.
     */
    fun next(current: Language, spoken: Language?, streak: Int): Decision = when {
        spoken == null -> Decision(current, streak)
        spoken == current -> Decision(current, 0)
        streak + 1 >= SWITCH_AFTER -> Decision(spoken, 0)
        else -> Decision(current, streak + 1)
    }

    /** What [next] decided, and the evidence to carry forward. */
    data class Decision(val language: Language, val streak: Int)

    /**
     * Questions in the other language before the recogniser is re-pointed at it.
     *
     * Two. Raising it makes the ear stubborn — a customer who really has switched keeps being
     * mis-heard — and lowering it to one is the behaviour this exists to prevent.
     */
    private const val SWITCH_AFTER = 2
}
