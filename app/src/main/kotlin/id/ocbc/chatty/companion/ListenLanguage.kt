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

    /**
     * The language to listen for after the recogniser heard speech and could make nothing of it.
     *
     * # Why a failure is evidence
     *
     * [next] learns from the *transcript*, so it learns nothing from a question that never became
     * one, and that is precisely the case where the ear is most likely to be wrong. Measured on a
     * handset with the switch on EN and an Indonesian question spoken into it: six consecutive
     * failures over eighty seconds, every one of them reporting that speech had been detected,
     * every retry using the same losing configuration, and the customer getting silence.
     *
     * Android's own bilingual hint is asked for and is not enough — it is a hint, and on this
     * handset it did not save the case it exists for. So an unusable recognition counts against the
     * current language, and enough of them in a row move the ear. Speech that will not transcribe
     * in the language being listened for is the strongest available signal that it is in the other
     * one, because the alternative explanations — a cough, a passing truck — do not repeat.
     *
     * [proven] is whether this ear has actually transcribed something in this conversation yet, and
     * it is what the threshold turns on. Once the ear has been shown to work, a failure is more
     * likely to be a cough or a passing truck than a language change, so it takes two. Before that
     * there is no evidence at all that the ear is right, the switch is only ever a default someone
     * may not have touched, and the first failure is already the best information available.
     *
     * That case is not a corner. It is the most common way this app is first used: the switch sits
     * where it was left, the customer speaks whichever language they think in, and the opening
     * question is the one that fails. Making them repeat themselves twice before being heard is the
     * first impression, and for this product the first impression is the product.
     *
     * ```
     * ListenLanguage.afterFailure(Language.ENGLISH, failures = 1, proven = false)
     * // Decision(INDONESIAN, 0) -- nothing has worked yet, so one failure is enough
     *
     * ListenLanguage.afterFailure(Language.ENGLISH, failures = 1, proven = true)
     * // Decision(ENGLISH, 1)   -- this ear works; one failure is noise, wait for a second
     * ```
     */
    fun afterFailure(current: Language, failures: Int, proven: Boolean): Decision {
        val enough = if (proven) SWITCH_AFTER else SWITCH_AFTER_UNPROVEN
        return if (failures >= enough) Decision(current.toggled(), 0) else Decision(current, failures)
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

    /**
     * Failures needed before the ear moves when it has never yet been shown to work.
     *
     * One. There is nothing to weigh against it: the language being listened for is a default
     * nobody has confirmed, and speech that will not transcribe in it is the only evidence there is.
     */
    private const val SWITCH_AFTER_UNPROVEN = 1
}
