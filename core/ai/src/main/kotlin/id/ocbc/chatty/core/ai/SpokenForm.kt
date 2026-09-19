package id.ocbc.chatty.core.ai

/**
 * Rewrites the numbers in a line into the words an Indonesian voice should say, leaving every other
 * character alone.
 *
 * # Why the eye and the ear need different text
 *
 * The transcript shows `Rp3.240.000` because that is what the customer matches, digit for digit,
 * against their own account. A synthesizer handed those same characters reads a run of digits and
 * full stops. ElevenLabs' low-latency models disable number normalisation by default and normalise
 * to *English* conventions when it is enabled at all, so the pronunciation has to be ours to make.
 *
 * This is a pronunciation hint, in the same category as the voice or the speaking rate. Nothing on
 * the wire changes: the transcript still carries the digits.
 *
 * ```
 * spokenForm("Saldo kamu Rp3.240.000, naik 1,2%.")
 * // "Saldo kamu tiga juta dua ratus empat puluh ribu rupiah, naik satu koma dua persen."
 * ```
 *
 * # Why this cannot invent a figure
 *
 * It never cuts text — cutting is how a rewriter manufactures a number, by truncating
 * `Rp852.300.000` to `Rp852`. It replaces one token with a spelling of that same token, and
 * [speakNumber] refuses to return a spelling it cannot parse *back* to the original digits. A figure
 * that cannot be spelled with certainty is left as digits: awkward, and correct.
 */
fun spokenForm(text: String, language: Language): String {
    // The two spellers are kept apart, and this is a correctness gate rather than a nicety. Every
    // Indonesian rule below reads "." as a thousands separator and "," as a decimal point, which is
    // the exact opposite of English: run it over an English sentence and "Rp1,200,000" is spoken as
    // "satu koma dua rupiah" — a balance misstated by a factor of a thousand, out loud, to the
    // person who owns it. The English speller makes the same bargain in reverse.
    val spelled = if (language == Language.INDONESIAN) spokenIndonesian(text) else spokenEnglish(text)
    return spelled.sayRates()
}

/**
 * Reads a rate's slash out loud: `Rp5.000.000/bulan` becomes "…rupiah per bulan".
 *
 * # Why this is the app's job now
 *
 * It used not to be. The synthesizer's own text normalization expanded "/" into the right word, and
 * that normalization is switched off here because it costs latency on every clause and this file
 * already does the part that matters — the figures. Turning it off took the slash with it, and a
 * slash the voice cannot say is a slash it drops: "five million rupiah bulan", which is not an
 * amount anybody states.
 *
 * "per" is the same word in both languages, which is why one rule serves both. It is also right for
 * a fraction read aloud in Indonesian — `3/12` is "tiga per dua belas" — and only loosely right in
 * English, where "three per twelve" is understandable but not how anyone says it. These answers are
 * about money per period, where it is exactly right, so that is the case it serves.
 */
private fun String.sayRates(): String = replace(RATE_SLASH, " per ")

/**
 * A slash with no space around it, which is how a rate is written and how a date is not.
 *
 * Bounded on both sides so that a slash already spoken as a separator — someone writing "A / B" —
 * is left alone, and so the replacement cannot double a space that was already there.
 */
private val RATE_SLASH = Regex("""(?<=\S)/(?=\S)""")

/**
 * Spells the numbers in an English line into English words.
 *
 * # Why the synthesizer cannot be left to do this
 *
 * This used to only re-punctuate — `Rp86.400.000` became `86,400,000 rupiah` — on the assumption
 * that reading English numerals is something the synthesizer already does well. It is not, on the
 * model this app runs. ElevenLabs applies text normalisation (the pass that turns numerals into
 * words) per model, and it is **off by default on `eleven_flash_v2_5`** to protect the latency that
 * is the whole reason Flash was chosen; `apply_text_normalization: "on"` is an Enterprise-only
 * override there. So the digits reached the voice raw, and a raw voice reading `2,000,000` says
 * "two thousand thousand" — a balance misstated by a factor of five hundred, in English, to the
 * person who owns it.
 *
 * Spelling it here is the fix that does not depend on a vendor setting, a plan tier, or a model
 * swap that would cost the latency Flash was picked for.
 *
 * ```
 * spokenForm("Your salary account holds Rp86.400.000.", Language.ENGLISH)
 * // "Your salary account holds eighty-six million four hundred thousand rupiah."
 * ```
 *
 * # What it refuses to read
 *
 * English and Indonesian disagree about what "." and "," mean, and an English answer about an
 * Indonesian bank account contains both conventions. Where the written form is certain — more than
 * one separator of a kind, which only one convention ever produces — it is read that way. Where it
 * is not, [canonicalizeEnglish] states the reading it picks and why, and anything outside those
 * shapes is left as digits: awkward, and correct.
 */
private fun spokenEnglish(text: String): String =
    replaceNumbers(
        speakEnglishBasisPoints(
            speakEnglishScaledAmounts(
                speakEnglishDates(speakModelNames(text, ::speakEnglishNumber)),
            ),
        ),
        ENGLISH_NUMBER_TOKEN,
    ) { token, joinedToAFigure ->
        if (joinedToAFigure) token else speakEnglishNumber(token) ?: token
    }

/**
 * Rewrites an ISO date as the words an English speaker says: `2026-09-10` becomes "the tenth of
 * September two thousand twenty-six".
 *
 * # Why the speller cannot do this
 *
 * The same reason [speakDates] exists. [replaceNumbers] sees three tokens, not one day, and the
 * hyphens make two of them look like signed figures — so a maturity date came out as a magnitude
 * and two counts, or, once the hyphen rule had claimed them, as the raw digits a non-normalising
 * voice reads character by character. Neither is a date. This pass claims the whole span first, and
 * because its output carries no digits the number pass that follows leaves it alone.
 *
 * Runs first, before the scale and basis-point passes, for the same reason: `09` inside a date is
 * not a figure.
 *
 * # Why day-month-year, and not "September tenth"
 *
 * The date is about an Indonesian account and was written in an Indonesian order, which is also the
 * order [speakDates] says it in. Keeping that order in English keeps the two voices saying the same
 * date the same way, and sidesteps the day/month transposition that reading `2026-09-10` as an
 * American date would invite.
 *
 * ```
 * spokenForm("It matures 2026-09-10.", Language.ENGLISH)
 * // "It matures the tenth of September two thousand twenty-six."
 * ```
 *
 * # Why this cannot invent a date
 *
 * A month outside 1–12 or a day outside 1–31 is not a date this understands, and the span is handed
 * back untouched rather than guessed at — the same bargain [speakEnglishNumber] makes. Calendar
 * length is deliberately not checked: 31 February is the API's mistake to make, and silently saying
 * a different day would hide it.
 */
private fun speakEnglishDates(text: String): String = ISO_DATE.replace(text) { match ->
    val (year, month, day) = match.destructured
    val name = ENGLISH_MONTHS.getOrNull(month.toInt() - 1) ?: return@replace match.value
    val ordinal = ENGLISH_ORDINALS.getOrNull(day.toInt() - 1) ?: return@replace match.value
    val spokenYear = speakEnglishNumber(year) ?: return@replace match.value
    "the $ordinal of $name $spokenYear"
}

/**
 * Rewrites "+12bp" as "plus twelve basis points".
 *
 * Runs before the general speller because the unit sits *outside* the numeric token: left alone, the
 * speller would read a bare "12" as a plain quantity and the voice would spell "bp" as two letters.
 *
 * English inflects the unit where Indonesian does not — one basis point, two basis points — so the
 * singular is keyed off the figure's own value rather than assumed.
 */
private fun speakEnglishBasisPoints(text: String): String = BASIS_POINTS.replace(text) { match ->
    val sign = match.groupValues[1]
    val figure = match.groupValues[2]
    val words = speakEnglishNumber(figure) ?: return@replace match.value
    val prefix = when (sign) {
        "+" -> "plus "
        "-" -> "minus "
        else -> ""
    }
    val unit = if (canonicalizeEnglish(figure) == "1") "basis point" else "basis points"
    "$prefix$words $unit"
}

/**
 * Translates a figure written with an Indonesian scale word — `Rp2,7 juta`, `Rp500 ribu` — into the
 * English one, in the order English says it in.
 *
 * # Why the general speller cannot do this
 *
 * The same reason [speakScaledAmounts] exists, plus a translation. Left alone, the speller sees
 * `Rp2,7` and a separate word `juta`, and it puts the currency at the end of its own token: "two
 * point seven **rupiah juta**", which is not a sum of money in any language, and leaves an
 * Indonesian word in an English sentence besides. The scale has to be part of what is rewritten, so
 * the whole span is claimed here, before the general pass sees it.
 *
 * The model writes these in English answers because it is describing an Indonesian account and
 * these are the words the account is described with.
 *
 * ```
 * spokenForm("Your savings are Rp2,7 juta.", Language.ENGLISH)
 * // "Your savings are two point seven million rupiah."
 * ```
 */
private fun speakEnglishScaledAmounts(text: String): String = SCALED_AMOUNT.replace(text) { match ->
    val (currency, figure, scale) = match.destructured
    val words = speakEnglishNumber(figure) ?: return@replace match.value
    val unit = if (currency.isNotBlank()) " rupiah" else ""
    "$words ${ENGLISH_SCALE_WORDS.getValue(scale.lowercase())}$unit"
}

private fun spokenIndonesian(text: String): String =
    replaceNumbers(
        speakBasisPoints(speakScaledAmounts(speakDates(speakModelNames(text, ::speakNumber)))),
    ) { token, joinedToAFigure ->
        // A hyphen between two figures joins a run this pass does not recognise — a partial date, a
        // reference number — and reading that out as separate quantities is worse than the digits.
        //
        // A hyphen between a figure and a *word* is a different thing entirely, and the two used to
        // be treated alike. Indonesian cannot attach a possessive clitic to digits, so people write
        // "Rp12.000.000-mu", and refusing to spell it is how an ordinary amount ended up read out as
        // characters in the customer's ear.
        if (joinedToAFigure) token else speakNumber(token) ?: token
    }.let(::attachClitics)

/**
 * Closes up a possessive clitic once the figure beside it has become words.
 *
 * `Rp12.000.000-mu` spells to `dua belas juta rupiah-mu`, and that hyphen only ever existed because a
 * clitic cannot be written against digits. With words on its left, Indonesian writes it closed —
 * `rupiahmu`. The synthesizer reads the closed form as one word and the hyphenated form as two,
 * which is the difference between a sentence and a stumble.
 *
 * ```
 * attachClitics("dua belas juta rupiah-mu")   // "dua belas juta rupiahmu"
 * ```
 */
private fun attachClitics(text: String): String =
    SPELLED_CLITIC.replace(text) { it.groupValues[1] + it.groupValues[2] }

/**
 * The words for one numeric token, or null when it cannot be spelled with certainty.
 *
 * Null is the important half of the contract. Returning a best guess would mean a wrong magnitude
 * said out loud about someone's money, which is worse than the digits read awkwardly.
 */
internal fun speakNumber(token: String): String? {
    val canonical = canonicalize(token)
    val negative = canonical.startsWith("-")
    val digits = canonical.removePrefix("-")

    val whole = digits.substringBefore('.')
    val fraction = digits.substringAfter('.', missingDelimiterValue = "")

    val value = whole.toULongOrNull() ?: return null
    if (value > MAX_SPELLABLE) return null

    var said = spellWhole(value)
    if (fraction.isNotEmpty()) {
        val fractionWords = spellDigits(fraction) ?: return null
        said += " $DECIMAL_POINT $fractionWords"
    }

    // The inverse. Anything it cannot reproduce exactly is not spoken.
    if (parseSpoken(said) != digits) return null

    return (if (negative) "$MINUS $said" else said) + spokenSuffix(token)
}

/**
 * Strips a written figure down to plain digits: `"Rp3.240.000"` becomes `"3240000"`.
 *
 * Indonesian groups thousands with "." and marks the decimal with ",", which is the opposite of the
 * English convention and the single most likely thing to get backwards here.
 */
internal fun canonicalize(token: String): String {
    var s = token.trim().removeSuffix("%").replace(" ", "")

    // Peel sign and currency symbol in any order: "-Rp1.234" and "Rp-1.234" are both written.
    var negative = false
    while (s.isNotEmpty()) {
        when {
            s[0] == '-' -> { negative = !negative; s = s.substring(1) }
            s[0] == '+' -> s = s.substring(1)
            s.length >= 2 && s.take(2).equals("Rp", ignoreCase = true) -> s = s.substring(2)
            else -> break
        }
    }

    s = s.replace(".", "")
    val whole = s.substringBefore(',').trimStart('0').ifEmpty { "0" }
    val fraction = s.substringAfter(',', missingDelimiterValue = "").trimEnd('0')

    val out = if (fraction.isEmpty()) whole else "$whole.$fraction"
    return if (negative && out != "0") "-$out" else out
}

/**
 * Renders a whole number.
 *
 * Indonesian contracts "satu" to the prefix "se-" before *sepuluh*, *seratus* and *seribu*, but not
 * before *juta*, *miliar* or *triliun* — "satu juta", never "sejuta", in formal speech. Getting that
 * wrong is what makes a bank's companion sound like a tourist, so the contraction is decided per
 * scale rather than globally.
 */
private fun spellWhole(value: ULong): String {
    if (value == 0UL) return UNITS[0]

    var remaining = value
    val parts = mutableListOf<String>()
    for ((scale, word) in SCALES) {
        if (remaining < scale) continue
        val count = remaining / scale
        remaining %= scale
        if (count == 1UL && word == "ribu") parts += "seribu" else parts += listOf(spellWhole(count), word)
    }
    if (remaining > 0UL) parts += spellBelowThousand(remaining)
    return parts.joinToString(" ")
}

/** Renders 1–999, where all of Indonesian's irregular forms live. */
private fun spellBelowThousand(value: ULong): String {
    val parts = mutableListOf<String>()
    var n = value

    val hundreds = n / 100UL
    if (hundreds > 0UL) {
        if (hundreds == 1UL) parts += "seratus" else parts += listOf(UNITS[hundreds.toInt()], "ratus")
        n %= 100UL
    }

    val tail = n.toInt()
    when {
        tail == 0 -> Unit
        tail < 10 -> parts += UNITS[tail]
        tail == 10 -> parts += "sepuluh"
        tail == 11 -> parts += "sebelas"
        tail < 20 -> parts += listOf(UNITS[tail - 10], "belas")
        else -> {
            parts += listOf(UNITS[tail / 10], "puluh")
            if (tail % 10 > 0) parts += UNITS[tail % 10]
        }
    }
    return parts.joinToString(" ")
}

/**
 * Reads a fraction digit by digit, which is how a decimal is spoken: "0,85" is
 * "nol koma delapan lima", never "nol koma delapan puluh lima".
 */
private fun spellDigits(digits: String): String? = digits
    .map { if (it in '0'..'9') UNITS[it - '0'] else return null }
    .joinToString(" ")

/**
 * The inverse of the speller: words back to the digits they claim to be, or null.
 *
 * This exists only to be compared against the input. It is cheaper to write the inverse than to
 * prove the speller correct, and a mismatch is caught before anything is said rather than after.
 */
private fun parseSpoken(said: String): String? {
    val (wholeWords, fractionWords) = said.split(" $DECIMAL_POINT ", limit = 2)
        .let { it[0] to it.getOrNull(1) }

    val whole = parseWholeWords(wholeWords) ?: return null
    if (fractionWords == null) return whole.toString()

    val fraction = parseDigitWords(fractionWords) ?: return null
    return "$whole.$fraction"
}

/**
 * Walks the words left to right, accumulating a value the same way a person reads them aloud.
 *
 * A bare unit word (e.g. "dua") is held in [pending] because its meaning depends on what follows it:
 * "dua" then "puluh" is 20, "dua" then "ratus" is 200, but "dua" alone — followed by a scale word or
 * the end of the text — is just 2. [group] accumulates a below-thousand chunk ([spellBelowThousand]'s
 * inverse); it is added into [total] at its scale ("ribu", "juta", …) or, for the last chunk, at the
 * very end. Any word this speller would never emit — or a shape it would never emit it in, such as
 * two bare units in a row — returns null rather than guessing.
 */
private fun parseWholeWords(text: String): ULong? {
    var total = 0UL
    var group = 0UL
    var pending = 0UL
    var hasPending = false

    fun flushPending() {
        if (hasPending) {
            group += pending
            pending = 0UL
            hasPending = false
        }
    }

    for (word in text.split(' ').filter(String::isNotEmpty)) {
        val unit = UNITS.indexOf(word).takeIf { it >= 0 }
        when {
            unit != null -> {
                if (hasPending) return null // two bare units running is not a number this speller emits
                pending = unit.toULong()
                hasPending = true
            }
            word == "sepuluh" -> { if (hasPending) return null; group += 10UL }
            word == "sebelas" -> { if (hasPending) return null; group += 11UL }
            word == "seratus" -> { if (hasPending) return null; group += 100UL }
            word == "seribu" -> { flushPending(); total += 1_000UL; group = 0UL }
            word == "belas" -> { if (!hasPending) return null; group += 10UL + pending; pending = 0UL; hasPending = false }
            word == "puluh" -> { if (!hasPending) return null; group += pending * 10UL; pending = 0UL; hasPending = false }
            word == "ratus" -> { if (!hasPending) return null; group += pending * 100UL; pending = 0UL; hasPending = false }
            else -> {
                val scale = SCALES.firstOrNull { it.second == word }?.first ?: return null
                flushPending()
                if (group == 0UL) return null
                total += group * scale
                group = 0UL
            }
        }
    }
    flushPending()
    return total + group
}

private fun parseDigitWords(text: String): String? = text
    .split(' ')
    .filter(String::isNotEmpty)
    .map { word -> UNITS.indexOf(word).takeIf { it >= 0 }?.toString() ?: return null }
    .joinToString("")

/**
 * The currency or percent decoration.
 *
 * Indonesian says the unit *after* the amount ("… rupiah"), unlike the written form which prefixes
 * "Rp". These are outside the round-trip check: they are fixed strings keyed off the token's own
 * decoration, with no arithmetic to get wrong.
 */
private fun spokenSuffix(token: String): String {
    val t = token.trim()
    return when {
        t.endsWith("%") -> " persen"
        t.contains("rp", ignoreCase = true) -> " rupiah"
        else -> ""
    }
}

/**
 * Rewrites an ISO date as the words an Indonesian speaker says: `2026-09-10` becomes
 * "sepuluh September dua ribu dua puluh enam".
 *
 * # Why the speller cannot do this
 *
 * [replaceNumbers] sees three tokens, not one day. Read as quantities they come out "dua ribu dua
 * puluh enam, sembilan, sepuluh" — a magnitude, a month that sounds like a count, and a day that
 * sounds like another. So dates were left as digits, and the voice read "2026-09-10" character by
 * character. Neither is a date. This pass claims the whole span first, and because its output
 * carries no digits the number pass that follows leaves it alone.
 *
 * Runs first, before the basis-point pass, for the same reason: `09` inside a date is not a figure.
 *
 * ```
 * speakDates("Jatuh tempo 2026-09-10.")
 * // "Jatuh tempo sepuluh September dua ribu dua puluh enam."
 * ```
 *
 * # Why this cannot invent a date
 *
 * A month outside 1–12 or a day outside 1–31 is not a date this understands, and the span is handed
 * back untouched rather than guessed at — the same bargain [speakNumber] makes. Calendar length is
 * deliberately not checked: 31 February is the API's mistake to make, and silently saying a
 * different day would hide it. The day and year go through [speakNumber], which verifies its own
 * spelling, so a number is never said unless it parses back to the digits it came from.
 */
private fun speakDates(text: String): String = ISO_DATE.replace(text) { match ->
    val (year, month, day) = match.destructured
    val name = MONTHS.getOrNull(month.toInt() - 1) ?: return@replace match.value
    if (day.toInt() !in 1..31) return@replace match.value
    val spokenDay = speakNumber(day.trimStart('0')) ?: return@replace match.value
    val spokenYear = speakNumber(year) ?: return@replace match.value
    "$spokenDay $name $spokenYear"
}

/**
 * Rewrites a figure written with a scale word — `Rp2,7 juta`, `Rp500 ribu`, `1,5 miliar` — into the
 * order Indonesian actually says it in.
 *
 * # Why the general speller cannot do this
 *
 * It sees `Rp2,7` and a separate word `juta`, and it puts the currency where the currency goes: at
 * the end of its own token. The result is "dua koma tujuh **rupiah juta**", which is not a sum of
 * money in any language. The unit belongs after the scale, so the scale has to be part of what is
 * being rewritten — and that means claiming the whole span here, before the general pass sees it.
 *
 * ```
 * speakScaledAmounts("Tabungan kamu Rp2,7 juta.")
 * // "Tabungan kamu dua koma tujuh juta rupiah."
 * ```
 *
 * `rb` and `jt` are accepted because the model writes them; `M` and `T` are not, because they are
 * ambiguous enough to turn a balance into a different balance, and digits read awkwardly beat a
 * figure read wrongly.
 */
private fun speakScaledAmounts(text: String): String = SCALED_AMOUNT.replace(text) { match ->
    val (currency, figure, scale) = match.destructured
    val words = speakNumber(figure) ?: return@replace match.value
    val unit = if (currency.isNotBlank()) " rupiah" else ""
    "$words ${SCALE_WORDS.getValue(scale.lowercase())}$unit"
}

/**
 * Rewrites "+12bp" as "plus dua belas basis poin".
 *
 * Runs before the general speller because the unit sits *outside* the numeric token: left alone, the
 * speller would see a bare "12" and read it as a plain quantity, and the "bp" would be spelled out
 * as letters.
 */
private fun speakBasisPoints(text: String): String = BASIS_POINTS.replace(text) { match ->
    val sign = match.groupValues[1]
    val words = speakNumber(match.groupValues[2]) ?: return@replace match.value
    val prefix = when (sign) {
        "+" -> "plus "
        "-" -> "minus "
        else -> ""
    }
    "$prefix$words basis poin"
}

/**
 * Separates a product name's trailing letter from its number: `iPhone 17e` becomes
 * `iPhone seventeen E`.
 *
 * # Why this pass exists
 *
 * [replaceNumbers] deliberately refuses any token welded to a letter, because that shape is usually
 * an identifier — `SBN-SR021` is a bond series, not a quantity, and reading it as one would be
 * worse than reading the characters. The trailing-letter case is the exception that costs the
 * customer something: a voice with no normalisation reads `17e` as neither "seventeen E" nor
 * "one seven E" but as a stumble, and model names are exactly the thing an advisor says out loud.
 *
 * Narrow on purpose. It claims a run of digits with **one** letter welded to its end, and nothing
 * on either side. That leaves ordinals alone (`1st`, `2nd` — two letters), leaves unit suffixes
 * alone (`10km`), leaves `+12bp` to [speakBasisPoints], and leaves anything with letters in front of
 * the digits — `SR021`, and `Rp2.000.000` — untouched. The letter is upper-cased because that is
 * how it is said: the "e" in "iPhone 17e" is a letter being named, not a sound.
 *
 * [speak] is the caller's own number speller, so the same pass serves both languages and the digits
 * come out in the language being spoken. A figure it declines to spell is left exactly as written.
 *
 * ```
 * speakModelNames("The iPhone 17e is out.", ::speakEnglishNumber)
 * // "The iPhone seventeen E is out."
 * ```
 */
private fun speakModelNames(text: String, speak: (String) -> String?): String =
    MODEL_NAME.replace(text) { match ->
        val (digits, letter) = match.destructured
        val words = speak(digits) ?: return@replace match.value
        "$words ${letter.uppercase()}"
    }

/**
 * The words for one English numeric token, or null when it cannot be spelled with certainty.
 *
 * Null is the important half of the contract, exactly as it is in [speakNumber]: a best guess means
 * a wrong magnitude said out loud about someone's money, which is worse than the digits read
 * awkwardly. The spelling is checked against [parseEnglishSpoken] — the inverse — before it is
 * returned, so a number is never said unless it parses back to the digits it came from.
 */
internal fun speakEnglishNumber(token: String): String? {
    val canonical = canonicalizeEnglish(token) ?: return null
    val negative = canonical.startsWith("-")
    val digits = canonical.removePrefix("-")

    val whole = digits.substringBefore('.')
    val fraction = digits.substringAfter('.', missingDelimiterValue = "")

    val value = whole.toULongOrNull() ?: return null
    if (value > MAX_SPELLABLE) return null

    var said = spellEnglishWhole(value)
    if (fraction.isNotEmpty()) {
        val fractionWords = spellEnglishDigits(fraction) ?: return null
        said += " $ENGLISH_DECIMAL_POINT $fractionWords"
    }

    if (parseEnglishSpoken(said) != digits) return null

    return (if (negative) "$ENGLISH_MINUS $said" else said) + englishSuffix(token)
}

/**
 * Strips an English-sentence figure down to plain digits, or null when it is written ambiguously.
 *
 * # The problem this has to solve
 *
 * An English answer about an Indonesian bank account carries both conventions at once: the model
 * writes `Rp86.400.000` because that is how the amount appears on the statement, and `2,500` because
 * that is how English writes a number. "." and "," mean opposite things in the two, so the reading
 * has to come from the shape of the figure rather than from a guess.
 *
 * More than one separator of a kind settles it outright: English never writes `86.400.000` and
 * Indonesian never writes `2,500,000`. A mixed pair settles itself too — whichever separator groups
 * triples is the grouping, and the other is the decimal.
 *
 * # The two shapes that do not settle themselves
 *
 * `1,200` and `1.234` are each a valid figure in both conventions, and no amount of looking at them
 * will say which. Each gets one stated reading rather than a guess per call site:
 *
 *  - `1,200` reads as English grouping — twelve hundred. This is English text; a comma followed by
 *    exactly three digits is overwhelmingly a thousands separator here, and reading it as a decimal
 *    would divide the figure by a thousand.
 *  - `1.234` reads as an English decimal, **unless** the figure carries "Rp". Rupiah has no
 *    sub-unit anyone quotes, so `Rp1.234` is one thousand two hundred thirty-four rupiah; without
 *    the currency there is nothing to say it is not one point two three four.
 *
 * Anything outside these shapes — `1.2.3`, `12,34,567` — returns null and is left as digits.
 */
internal fun canonicalizeEnglish(token: String): String? {
    var s = token.trim().removeSuffix("%").replace(" ", "")

    // Peel sign and currency symbol in any order: "-Rp1.234" and "Rp-1.234" are both written, and
    // the model writes the symbol as "Rp", "Rp." or "Rp ".
    var negative = false
    var currency = false
    while (s.isNotEmpty()) {
        when {
            s[0] == '-' -> { negative = !negative; s = s.substring(1) }
            s[0] == '+' -> s = s.substring(1)
            s.length >= 2 && s.take(2).equals("Rp", ignoreCase = true) -> {
                currency = true
                s = s.substring(2).removePrefix(".")
            }
            else -> break
        }
    }

    val dots = s.count { it == '.' }
    val commas = s.count { it == ',' }

    val (whole, fraction) = when {
        PLAIN_DIGITS.matches(s) -> s to ""

        // Certain from the shape alone: only one convention ever writes this.
        dots >= 2 -> DOT_GROUPED.matchEntire(s)?.destructured?.let { (g, f) -> g.replace(".", "") to f }
            ?: return null
        commas >= 2 -> COMMA_GROUPED.matchEntire(s)?.destructured?.let { (g, f) -> g.replace(",", "") to f }
            ?: return null

        // One of each: the separator that groups triples is the grouping, whichever it is.
        dots == 1 && commas == 1 ->
            DOT_GROUPED.matchEntire(s)?.destructured?.let { (g, f) -> g.replace(".", "") to f }
                ?: COMMA_GROUPED.matchEntire(s)?.destructured?.let { (g, f) -> g.replace(",", "") to f }
                ?: return null

        // The stated readings. See the doc comment for why each is the one it is.
        commas == 1 -> {
            val after = s.substringAfter(',')
            if (after.length == 3) s.replace(",", "") to ""
            else COMMA_DECIMAL.matchEntire(s)?.destructured?.toList()?.let { it[0] to it[1] } ?: return null
        }
        dots == 1 -> {
            val after = s.substringAfter('.')
            if (currency && after.length == 3) s.replace(".", "") to ""
            else DOT_DECIMAL.matchEntire(s)?.destructured?.toList()?.let { it[0] to it[1] } ?: return null
        }

        else -> return null
    }

    val magnitude = whole.trimStart('0').ifEmpty { "0" }
    val tail = fraction.trimEnd('0')
    val out = if (tail.isEmpty()) magnitude else "$magnitude.$tail"
    return if (negative && out != "0") "-$out" else out
}

/**
 * Renders a whole number in English.
 *
 * English groups by thousands and names every third power — thousand, million, billion, trillion —
 * so the shape is the same as [spellWhole]'s but the table is not a translation of it: Indonesian's
 * "miliar" sits where English says "billion", and Indonesian contracts "satu" to "se-" where English
 * never contracts anything.
 *
 * The tens are hyphenated — "twenty-one", not "twenty one" — because that is how English writes them
 * and the synthesizer reads the hyphenated form as one word. [parseEnglishWholeWords] splits on the
 * hyphen, so the round-trip check is unaffected.
 */
private fun spellEnglishWhole(value: ULong): String {
    if (value == 0UL) return ENGLISH_ONES[0]

    var remaining = value
    val parts = mutableListOf<String>()
    for ((scale, word) in ENGLISH_SCALES) {
        if (remaining < scale) continue
        parts += spellEnglishBelowThousand(remaining / scale)
        parts += word
        remaining %= scale
    }
    if (remaining > 0UL) parts += spellEnglishBelowThousand(remaining)
    return parts.joinToString(" ")
}

/** Renders 1–999, where all of English's irregular forms live. */
private fun spellEnglishBelowThousand(value: ULong): String {
    val parts = mutableListOf<String>()
    var n = value.toInt()

    if (n >= 100) {
        parts += ENGLISH_ONES[n / 100]
        parts += "hundred"
        n %= 100
    }

    when {
        n == 0 -> Unit
        n < 10 -> parts += ENGLISH_ONES[n]
        n < 20 -> parts += ENGLISH_TEENS[n - 10]
        n % 10 == 0 -> parts += ENGLISH_TENS[n / 10 - 2]
        else -> parts += "${ENGLISH_TENS[n / 10 - 2]}-${ENGLISH_ONES[n % 10]}"
    }
    return parts.joinToString(" ")
}

/**
 * Reads a fraction digit by digit, which is how a decimal is spoken: "0.85" is "zero point eight
 * five", never "zero point eighty-five".
 */
private fun spellEnglishDigits(digits: String): String? = digits
    .map { if (it in '0'..'9') ENGLISH_ONES[it - '0'] else return null }
    .joinToString(" ")

/**
 * The inverse of the English speller: words back to the digits they claim to be, or null.
 *
 * Exists only to be compared against the input, for the same reason [parseSpoken] does — it is
 * cheaper to write the inverse than to prove the speller correct, and a mismatch is caught before
 * anything is said rather than after.
 */
private fun parseEnglishSpoken(said: String): String? {
    val (wholeWords, fractionWords) = said.split(" $ENGLISH_DECIMAL_POINT ", limit = 2)
        .let { it[0] to it.getOrNull(1) }

    val whole = parseEnglishWholeWords(wholeWords) ?: return null
    if (fractionWords == null) return whole.toString()

    val fraction = parseEnglishDigitWords(fractionWords) ?: return null
    return "$whole.$fraction"
}

/**
 * Walks the words left to right, accumulating a value the same way a person reads them aloud.
 *
 * A bare unit word is held in `pending` because its meaning depends on what follows it: "two" then
 * "hundred" is 200, "two" then "million" is two million, but "two" at the end of a group is just 2.
 * `group` accumulates a below-thousand chunk ([spellEnglishBelowThousand]'s inverse) and is added
 * into `total` at its scale, or at the very end for the last chunk. Any word this speller would
 * never emit — or a shape it would never emit it in, such as two bare units running — returns null
 * rather than guessing.
 */
private fun parseEnglishWholeWords(text: String): ULong? {
    var total = 0UL
    var group = 0UL
    var pending: ULong? = null

    fun flushPending() {
        pending?.let { group += it }
        pending = null
    }

    for (word in text.split(' ', '-').filter(String::isNotEmpty)) {
        val one = ENGLISH_ONES.indexOf(word).takeIf { it >= 0 }
        val teen = ENGLISH_TEENS.indexOf(word).takeIf { it >= 0 }
        val ten = ENGLISH_TENS.indexOf(word).takeIf { it >= 0 }
        when {
            one != null -> {
                if (pending != null) return null // two bare units running is not a number this emits
                pending = one.toULong()
            }
            teen != null -> { if (pending != null) return null; group += (teen + 10).toULong() }
            ten != null -> { if (pending != null) return null; group += ((ten + 2) * 10).toULong() }
            word == "hundred" -> {
                val hundreds = pending ?: return null
                if (hundreds == 0UL) return null // "zero hundred" is not something this emits
                group += hundreds * 100UL
                pending = null
            }
            else -> {
                val scale = ENGLISH_SCALES.firstOrNull { it.second == word }?.first ?: return null
                flushPending()
                if (group == 0UL) return null
                total += group * scale
                group = 0UL
            }
        }
    }
    flushPending()
    return total + group
}

private fun parseEnglishDigitWords(text: String): String? = text
    .split(' ')
    .filter(String::isNotEmpty)
    .map { word -> ENGLISH_ONES.indexOf(word).takeIf { it >= 0 }?.toString() ?: return null }
    .joinToString("")

/**
 * The currency or percent decoration, in English.
 *
 * "rupiah" rather than "Indonesian rupiah" or "IDR": it is what the customer's own bank app says,
 * and it is the word an English-speaking advisor in Jakarta uses. Outside the round-trip check for
 * the same reason [spokenSuffix] is — fixed strings keyed off the token's own decoration, with no
 * arithmetic to get wrong.
 */
private fun englishSuffix(token: String): String {
    val t = token.trim()
    return when {
        t.endsWith("%") -> " percent"
        t.contains("rp", ignoreCase = true) -> " rupiah"
        else -> ""
    }
}

/**
 * Applies [transform] to every numeric token in [text], handing it the characters on either side.
 *
 * The token definition is shared with nothing else here, but it is deliberately the same one wow's
 * gate used: a span the gate would have checked is exactly a span this may rewrite, so a product id
 * like `SBN-SR021` is invisible to both.
 *
 * [pattern] is what a figure looks like in the language being spoken — [NUMBER_TOKEN] for
 * Indonesian, [ENGLISH_NUMBER_TOKEN] for English, which also has to recognise comma grouping. The
 * surrounding rules (a sign that is really a separator, a token welded to a letter, a hyphen joining
 * two figures) are the same either way, which is why there is one loop and not two.
 */
private fun replaceNumbers(
    text: String,
    pattern: Regex = NUMBER_TOKEN,
    transform: (token: String, joinedToAFigure: Boolean) -> String,
): String {
    val builder = StringBuilder(text.length)
    var cursor = 0

    for (match in pattern.findAll(text)) {
        var start = match.range.first
        val end = match.range.last + 1

        // A sign directly preceded by a digit is a separator, not a sign: "2026-07-09" is a date.
        if (start > 0 && (text[start] == '-' || text[start] == '+') && text[start - 1].isDigit()) start++
        if (start >= end) continue
        // A token touching a letter is an identifier, not a figure.
        if (start > 0 && text[start - 1].isLetter()) continue
        if (end < text.length && text[end].isLetter()) continue
        if (start < cursor) continue

        // Whether a hyphen beside this token joins it to another figure, as against joining it to a
        // word. Decided here because it needs the characters on the far side of the hyphen, which
        // only this loop can see.
        val joinedBefore = start > 1 && text[start - 1] == '-' && text[start - 2].isDigit()
        val joinedAfter = end + 1 < text.length && text[end] == '-' && text[end + 1].isDigit()

        builder.append(text, cursor, start)
        builder.append(transform(text.substring(start, end), joinedBefore || joinedAfter))
        cursor = end
    }
    builder.append(text, cursor, text.length)
    return builder.toString()
}

/**
 * Largest first; spelling consumes them in order.
 *
 * Indonesian groups by thousands like English but has a distinct word for 10^9 — "miliar", not "a
 * thousand million" — so this is not a translation of the English table.
 */
private val SCALES = listOf(
    1_000_000_000_000UL to "triliun",
    1_000_000_000UL to "miliar",
    1_000_000UL to "juta",
    1_000UL to "ribu",
)

private val UNITS = listOf(
    "nol", "satu", "dua", "tiga", "empat", "lima", "enam", "tujuh", "delapan", "sembilan",
)

/**
 * A ceiling on what will be said out loud. One triliun past the largest balance anyone will demo,
 * and past it the digits are read instead — merely awkward, where a wrong magnitude word would be a
 * misstatement of someone's wealth.
 */
private const val MAX_SPELLABLE = 999_999_999_999_999UL

private const val DECIMAL_POINT = "koma"
private const val MINUS = "minus"

/**
 * One written figure: an optional sign, an optional "Rp", then either a thousands-grouped run
 * (`3.240.000`) or a plain run of digits, an optional comma-decimal, and an optional "%". The sign is
 * matched on both sides of "Rp" because both `-Rp1.234` and `Rp-1.234` occur in the wild.
 */
private val NUMBER_TOKEN =
    Regex("""[-+]?(?:Rp\.?\s*)?[-+]?(?:\d{1,3}(?:\.\d{3})+|\d+)(?:,\d+)?%?""", RegexOption.IGNORE_CASE)

/**
 * A figure carrying a scale word: an optional "Rp" (written "Rp", "Rp." or "Rp "), the figure, then
 * the scale. Kept separate from [NUMBER_TOKEN] because the currency has to move to the far side of
 * the scale word, which is only possible while both are still one match.
 */
private val SCALED_AMOUNT = Regex(
    """(Rp\.?\s*)?([-+]?(?:\d{1,3}(?:\.\d{3})+|\d+)(?:,\d+)?)\s*(ribu|juta|miliar|milyar|triliun|rb|jt)\b""",
    RegexOption.IGNORE_CASE,
)

/** Scale words as they are written, mapped to how they are said. */
private val SCALE_WORDS = mapOf(
    "ribu" to "ribu",
    "rb" to "ribu",
    "juta" to "juta",
    "jt" to "juta",
    "miliar" to "miliar",
    "milyar" to "miliar",
    "triliun" to "triliun",
)

/**
 * A bare ISO date: `2026-09-10`. Bounded by non-digits so it cannot bite a chunk out of a longer
 * run, and deliberately not extended to `10/09/2026` — that form is ambiguous with a ratio, and a
 * date said with the day and month swapped is worse than one said as digits.
 */
private val ISO_DATE = Regex("""(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)""")

/** Indonesian month names, indexed from January. Capitalised, as they are in writing. */
private val MONTHS = listOf(
    "Januari", "Februari", "Maret", "April", "Mei", "Juni",
    "Juli", "Agustus", "September", "Oktober", "November", "Desember",
)

/**
 * One written figure inside English text. [NUMBER_TOKEN] with comma grouping allowed as well as dot
 * grouping, and either separator able to open the decimal tail, because an English answer about an
 * Indonesian account carries both conventions — `2,500,000.50` and `Rp2.500.000,50` alike.
 * [canonicalizeEnglish] decides which convention a given match was written in.
 */
private val ENGLISH_NUMBER_TOKEN = Regex(
    """[-+]?(?:Rp\.?\s*)?[-+]?(?:\d{1,3}(?:[.,]\d{3})+|\d+)(?:[.,]\d+)?%?""",
    RegexOption.IGNORE_CASE,
)

/** Thousands grouped the Indonesian way, with an optional comma-decimal: `2.500.000,50`. */
private val DOT_GROUPED = Regex("""(\d{1,3}(?:\.\d{3})+)(?:,(\d+))?""")

/** Thousands grouped the English way, with an optional dot-decimal: `2,500,000.50`. */
private val COMMA_GROUPED = Regex("""(\d{1,3}(?:,\d{3})+)(?:\.(\d+))?""")

/** An English decimal: `1.2`. */
private val DOT_DECIMAL = Regex("""(\d+)\.(\d+)""")

/** An Indonesian decimal: `1,2`. */
private val COMMA_DECIMAL = Regex("""(\d+),(\d+)""")

/** A figure with no separator at all, which both conventions write the same way. */
private val PLAIN_DIGITS = Regex("""\d+""")

/**
 * A product name's trailing letter: the `17e` in `iPhone 17e`, the `5G` in `5G`. One letter only,
 * with nothing welded to either end — see [speakModelNames] for why it is drawn this narrowly.
 */
private val MODEL_NAME = Regex("""(?<![\p{L}\p{N}])(\d{1,4})([A-Za-z])(?![\p{L}\p{N}])""")

/** The English units, indexed by their own value, so the list is its own lookup table. */
private val ENGLISH_ONES = listOf(
    "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
)

/** 10–19, which English names rather than composing. Indexed from ten. */
private val ENGLISH_TEENS = listOf(
    "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen",
    "eighteen", "nineteen",
)

/** The tens, 20–90. Indexed from twenty, so the index is `n / 10 - 2`. */
private val ENGLISH_TENS = listOf(
    "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety",
)

/**
 * Largest first; spelling consumes them in order.
 *
 * The short scale, where "billion" is 10^9 — what English-speaking finance uses everywhere,
 * including the Indonesian "miliar" this sits opposite in [SCALES].
 */
private val ENGLISH_SCALES = listOf(
    1_000_000_000_000UL to "trillion",
    1_000_000_000UL to "billion",
    1_000_000UL to "million",
    1_000UL to "thousand",
)

/**
 * Indonesian scale words as the model writes them, mapped to the English word for the same power.
 *
 * "miliar" is 10^9 and so is "billion" — the short scale, which is what both Indonesian and
 * English-speaking finance use. This is the one place the two number systems have to be lined up
 * against each other, and getting it wrong is a thousand-fold error either way.
 */
private val ENGLISH_SCALE_WORDS = mapOf(
    "ribu" to "thousand",
    "rb" to "thousand",
    "juta" to "million",
    "jt" to "million",
    "miliar" to "billion",
    "milyar" to "billion",
    "triliun" to "trillion",
)

/** English month names, indexed from January. Capitalised, as they are in writing. */
private val ENGLISH_MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

/**
 * The days of a month as they are said, indexed from the first.
 *
 * Written out rather than composed from a suffix rule, because the rule has more exceptions than
 * cases — first, second, third, fifth, eighth, ninth, twelfth and every -ieth are all irregular —
 * and a list of thirty-one entries can be read and checked, where the rule has to be trusted.
 */
private val ENGLISH_ORDINALS = listOf(
    "first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth", "tenth",
    "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth",
    "eighteenth", "nineteenth", "twentieth", "twenty-first", "twenty-second", "twenty-third",
    "twenty-fourth", "twenty-fifth", "twenty-sixth", "twenty-seventh", "twenty-eighth",
    "twenty-ninth", "thirtieth", "thirty-first",
)

private const val ENGLISH_DECIMAL_POINT = "point"
private const val ENGLISH_MINUS = "minus"

/**
 * A spelled word carrying a hyphenated possessive clitic: `rupiah-mu`, `juta-nya`. Indonesian has
 * exactly three, so the list is closed and cannot swallow a genuine hyphenated compound.
 */
private val SPELLED_CLITIC = Regex("""(\p{L}+)-(mu|nya|ku)\b""", RegexOption.IGNORE_CASE)

/** A signed figure immediately followed by "bp" or "bps": `+12bp`, `-0,5 bps`. */
private val BASIS_POINTS = Regex("""([+-]?)(\d+(?:[.,]\d+)?)\s*bps?\b""", RegexOption.IGNORE_CASE)
