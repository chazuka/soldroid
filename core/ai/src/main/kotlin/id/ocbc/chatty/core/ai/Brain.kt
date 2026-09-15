package id.ocbc.chatty.core.ai

/**
 * Which model answers, chosen by the customer before the conversation starts.
 *
 * # Why the labels say nothing
 *
 * "Model 1", not a vendor name. Whoever is judging these answers should be judging the answer, and
 * a brand on the button decides that for them before they have read a word — the one prior nobody
 * can un-see. The mapping lives here, in the code, and in the turn trace.
 *
 * # What is actually being compared
 *
 * Not models. [KAMARTAJ] answers with its own server-side persona prompt, which this app cannot
 * read; the other two answer with [advisorPrompt] and the same customer record. So this compares
 * three *stacks* — prompt and model together — which is the thing a customer experiences anyway.
 * Reading a result here as "one model beat another" would be reading it wrong.
 */
enum class Brain(
    /** What the chooser shows. Deliberately anonymous — see the class note. */
    val label: String,
) {
    /** The demo API's own persona, running `deepseek-v4-flash-0731` upstream. The baseline. */
    KAMARTAJ("Model 1"),

    /** Anthropic Claude, prompted by this app. */
    ANTHROPIC("Model 2"),

    /** OpenAI GPT, prompted by this app. */
    OPENAI("Model 3"),
    ;

    /** True when this app supplies the system prompt, and therefore needs the customer record. */
    val needsBriefing: Boolean get() = this != KAMARTAJ

    companion object {
        /** The one every conversation starts on, so an untouched chooser behaves as it always did. */
        val Default = KAMARTAJ
    }
}

/**
 * The brains a build can actually reach, and the client behind each.
 *
 * # Why a type and not a Map
 *
 * Two reasons, and the second is the one that matters. A map of enum to interface crosses into Java
 * as `Map<Brain, ? extends ChatClient>` and stops being injectable without wildcard suppression at
 * every site. More usefully, "which brains exist" and "fall back when one is missing" are rules
 * rather than lookups, and they belong in one place instead of being repeated by every caller.
 *
 * A brain with no key is simply absent: [available] is what the chooser offers, and [get] answers
 * with the default rather than throwing, so a stale selection cannot break a turn.
 *
 * ```
 * val chat = brains[state.brain]          // never null
 * brains.available                        // what the picker shows; hidden when it has one entry
 * ```
 */
class Brains(private val clients: Map<Brain, ChatClient>) {

    /** Offered in declaration order, so the numbering the customer sees is stable across builds. */
    val available: List<Brain> get() = Brain.entries.filter { it in clients }

    /** The client for [brain], or the default's when this build has no key for it. */
    operator fun get(brain: Brain): ChatClient =
        clients[brain] ?: clients.getValue(Brain.Default)
}
