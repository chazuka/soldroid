package id.ocbc.chatty.core.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The language a turn happens in.
 *
 * # Why one enum decides three things
 *
 * The personas answer in whatever language they are asked in. So the language picked here selects
 * the speech recogniser's model, *and* the voice the answer is spoken in, *and* (implicitly, by what
 * the customer typed or said) the language the model replies in. Splitting those into separate
 * settings would let them disagree — an Indonesian question answered in an Indonesian sentence read
 * aloud by an English voice — which is the exact failure this consolidates away.
 *
 * Indonesian leads because these customers and their records are Indonesian. English is one tap
 * away, and is also the fallback on a handset whose on-device `id-ID` recogniser has not downloaded.
 */
enum class Language(
    /** BCP-47 tag for the platform speech recogniser. */
    val tag: String,
    /** The two characters shown on the switch. */
    val label: String,
    /** The key this language uses in `agents.json`. */
    val key: String,
) {
    INDONESIAN("id-ID", "ID", "id"),
    ENGLISH("en-US", "EN", "en"),
    ;

    /** The other one. With exactly two languages, "switch" needs no menu. */
    fun toggled(): Language = if (this == INDONESIAN) ENGLISH else INDONESIAN

    companion object {
        /**
         * Which language [text] is written in, or null when it is not clear enough to act on.
         *
         * # Why the app has to work this out rather than be told
         *
         * The language switch picks the recogniser, the voice and the number speller together. It is
         * a statement about the *conversation*, and customers do not honour it: they ask in English
         * with the switch on ID, and the model answers in English. What then reaches the synthesizer
         * is an English sentence in an Indonesian voice, run through a speller that reads "," as a
         * decimal point — so "Rp1,200,000" is said as "satu koma dua rupiah". Detecting the language
         * of the words actually being spoken is what keeps those three in agreement.
         *
         * ```
         * Language.detect("Berapa saldo tabungan saya?")   // INDONESIAN
         * Language.detect("How much did I save?")          // ENGLISH
         * Language.detect("Rp2.500.000")                   // null — no words to go on
         * ```
         *
         * # Why a word list and not a model
         *
         * This runs on the first clause of every answer, before a single sample is synthesized, so
         * it has to be free. Function words are the strongest cheap signal there is — they are the
         * most common words in any sentence and the two languages share almost none of them. Null is
         * returned unless one side wins clearly, because the caller's fallback (whatever the
         * conversation is already in) is a better guess than a coin toss.
         */
        fun detect(text: String): Language? {
            var indonesian = 0
            var english = 0
            for (word in text.lowercase().split(NON_WORD)) {
                if (word in INDONESIAN_WORDS) indonesian++
                if (word in ENGLISH_WORDS) english++
            }
            return when {
                indonesian >= MIN_MARKERS && indonesian >= english * MARKER_MARGIN -> INDONESIAN
                english >= MIN_MARKERS && english >= indonesian * MARKER_MARGIN -> ENGLISH
                else -> null
            }
        }
    }
}

/** At least this many function words before a verdict is worth having. */
private const val MIN_MARKERS = 2

/** How far ahead the winner has to be. Two-to-one keeps a loanword or two from deciding it. */
private const val MARKER_MARGIN = 2

private val NON_WORD = Regex("""[^\p{L}]+""")

/**
 * Indonesian function words, plus the banking nouns this app says constantly. Deliberately excludes
 * anything English also uses — "di" is Indonesian, "data" is both, so only the first is here.
 */
private val INDONESIAN_WORDS = setOf(
    "yang", "dan", "di", "ke", "dari", "untuk", "dengan", "pada", "tidak", "nggak", "bukan",
    "saya", "aku", "kamu", "anda", "kita", "bisa", "sudah", "belum", "akan", "ada", "adalah",
    "itu", "ini", "juga", "kalau", "atau", "saja", "lebih", "kurang", "sekitar", "per", "jadi",
    "naik", "turun", "bulan", "tahun", "hari", "saldo", "rekening", "tabungan", "pengeluaran",
    "berapa", "mau", "ingin", "punya", "banget", "supaya", "agar", "karena", "seperti", "masih",
)

/**
 * English function words. Short and common by design: the test is which list a sentence's connective
 * tissue comes from, not how much vocabulary it shares.
 */
private val ENGLISH_WORDS = setOf(
    "the", "and", "you", "your", "yours", "is", "are", "was", "were", "be", "been", "this", "that",
    "these", "those", "for", "with", "from", "not", "can", "could", "will", "would", "should",
    "have", "has", "had", "about", "much", "many", "how", "what", "which", "when", "where", "why",
    "i", "me", "my", "we", "our", "it", "its", "but", "or", "if", "then", "than", "so", "also",
    "month", "year", "balance", "savings", "spending", "account", "more", "less", "want", "need",
)

/**
 * One selectable companion: who the server says it is, plus the face and voices this app gives it.
 */
data class Agent(
    val id: String,
    val displayName: String,
    val tagline: String,
    val avatar: AvatarProfile,
)

/**
 * The vendor ids that turn an agent id into a talking head, in every language it can speak.
 *
 * `avatarId` is a LiveAvatar UUID; the voices are ElevenLabs voice ids keyed by [Language.key].
 * None of it comes from the chat API — the API knows nothing about how its personas are rendered —
 * so all of it is configuration, carried in `assets/agents.json`.
 *
 * An agent needs a voice per language because the same persona has to sound like the same person in
 * both: Emma is a cheerful teenager whether she is being cheerful in Indonesian or in English.
 */
@Serializable
data class AvatarProfile(
    @SerialName("avatar_id") val avatarId: String,
    val voices: Map<String, String> = emptyMap(),
) {
    /**
     * The voice for [language], falling back to Indonesian and then to any configured voice.
     *
     * A deployment with one voice still speaks: the fallback means an answer is heard in the wrong
     * accent rather than not heard at all, and a silent avatar on stage is the worse failure.
     */
    fun voiceFor(language: Language): String =
        voices[language.key]
            ?: voices[Language.INDONESIAN.key]
            ?: voices.values.firstOrNull()
            ?: error("agents.json defines no voice for ${language.key}, and no fallback")
}

/**
 * `assets/agents.json`: one default profile, plus per-agent overrides keyed by the agent id the
 * chat API returns.
 *
 * ```json
 * {
 *   "default": { "avatar_id": "<uuid>", "voices": { "id": "<voice>", "en": "<voice>" } },
 *   "overrides": { "emma": { "voices": { "id": "<a warmer voice>" } } }
 * }
 * ```
 */
@Serializable
data class AgentProfiles(
    val default: AvatarProfile,
    val overrides: Map<String, AvatarProfileOverride> = emptyMap(),
) {
    /**
     * Merges an agent's override onto the default.
     *
     * Voices merge per language rather than wholesale, so an override that names only an English
     * voice keeps the default Indonesian one instead of silently losing it.
     */
    fun profileFor(agentId: String): AvatarProfile {
        val override = overrides[agentId] ?: return default
        return AvatarProfile(
            avatarId = override.avatarId ?: default.avatarId,
            voices = default.voices + override.voices,
        )
    }
}

/** A partial [AvatarProfile]: an agent may override the face, some voices, or all of it. */
@Serializable
data class AvatarProfileOverride(
    @SerialName("avatar_id") val avatarId: String? = null,
    val voices: Map<String, String> = emptyMap(),
)
