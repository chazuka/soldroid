package id.ocbc.chatty.core.ai.telemetry

/**
 * What this app is allowed to send to a telemetry backend.
 *
 * # Why this exists as a type rather than as SDK settings
 *
 * Observability SDKs decide for you by default, and their defaults are written for apps that are
 * not a bank. Sentry, for one, captures failed HTTP requests *including response bodies* out of the
 * box — and in this app the response body of `/api/customers/{persona}` is the customer's balances.
 * Left alone, switching telemetry on would ship the single most sensitive payload in the product.
 *
 * So the decision is hoisted out of the SDK and into one page that a reviewer can read end to end.
 * Every field below is one thing that either does or does not leave the handset, the default is
 * stated, and the comment says what you lose by turning it off and what leaks if you turn it on.
 * Changing what is sent is editing this file — not hunting through an options lambda.
 *
 * # How to change it
 *
 * Edit [Default]. It is the policy the app ships with, and the only instance anything constructs
 * unless a test is pinning behaviour. Nothing reads the SDK's own defaults, so a field added here
 * with a sensible value is the whole change.
 *
 * ```
 * // Debugging a vendor that keeps 500ing, on a build that never sees a real customer:
 * val policy = TelemetryPolicy.Default.copy(captureFailedRequests = true, scrubUrls = false)
 * ```
 */
data class TelemetryPolicy(
    /**
     * Whether a failed HTTP call becomes an error event of its own.
     *
     * Off, and this is the field to think hardest about. On, the backend receives the request URL,
     * the status, **and the response body** — which for the customer-record endpoint is the record.
     * Off, a failed call is still visible as a failed span on the turn's trace, with its status and
     * its duration, which is enough to see that a vendor is refusing and how often.
     *
     * Turn it on only against a build pointed at fixtures, never one pointed at a real record.
     */
    val captureFailedRequests: Boolean = false,

    /**
     * Whether HTTP calls leave a breadcrumb trail on events.
     *
     * On. Breadcrumbs are most of what makes an error report readable — the sequence of calls that
     * led to it — and they carry method, status and duration rather than payloads. The URLs they
     * carry are scrubbed when [scrubUrls] is set, which it is by default.
     */
    val httpBreadcrumbs: Boolean = true,

    /**
     * Whether URLs are rewritten to their shape before they are sent.
     *
     * On. `/api/customers/alvin` becomes `/api/customers/{persona}`: the endpoint stays legible, so
     * you can still group by it and time it, and which customer it was does not travel. See
     * [scrubUrl] for the rule.
     *
     * The personas in this demo are fictional, so today this protects a name that is not real. It is
     * on by default anyway, because the day this app points at an actual customer nobody will
     * remember to come back and turn it on.
     */
    val scrubUrls: Boolean = true,

    /**
     * Whether device and app context rides along: model, OS version, locale, app version.
     *
     * On. This is how "the renderer only starves on that one handset" becomes a question you can
     * answer rather than a rumour. None of it is customer data, and all of it is the kind of thing
     * you wish you had after the demo rather than during it.
     */
    val deviceContext: Boolean = true,

    /**
     * Whether a screenshot is attached when an error is reported.
     *
     * Off, and it should stay off. The screen at the moment of an error is a transcript of this
     * customer's finances — balances, goals, what they asked. A screenshot is the whole record in
     * one attachment, and no debugging value comes close to justifying it.
     */
    val attachScreenshot: Boolean = false,

    /**
     * Whether the view hierarchy is attached when an error is reported.
     *
     * Off, for the same reason as [attachScreenshot] and with the same conclusion: a Compose tree
     * can carry the text inside it, and that text is the answer the agent just gave.
     */
    val attachViewHierarchy: Boolean = false,

    /**
     * The SDK's own "send personally identifying information" switch.
     *
     * Off. It governs IP address, request headers, cookies and request bodies — every one of which
     * is something this app has no reason to send anywhere.
     */
    val sendDefaultPii: Boolean = false,

    /**
     * What fraction of turns are recorded, 0.0 to 1.0.
     *
     * All of them. A demo produces hundreds of turns a day, not millions, and sampling a small
     * population is how you end up with three data points for the handset that misbehaved. Lower it
     * only if volume ever becomes a bill.
     */
    val sampleRate: Double = 1.0,
) {
    init {
        require(sampleRate in 0.0..1.0) { "sampleRate must be between 0 and 1, was $sampleRate" }
    }

    /** True when nothing may be sent at all, which is what an empty backend key means. */
    val recordsNothing: Boolean get() = sampleRate == 0.0

    companion object {
        /** The policy this app ships with. Edit this to change what is sent. */
        val Default = TelemetryPolicy()
    }
}

/**
 * Rewrites a URL to its shape, dropping the parts that identify a person.
 *
 * `https://kamartaj.xyz/api/customers/alvin` becomes `https://kamartaj.xyz/api/customers/{persona}`.
 * The host and the path stay, because "which vendor, which endpoint" is the entire point of having
 * the URL at all; the identifier goes, because it is the one part that says *whose* money this call
 * was about.
 *
 * Query strings are dropped whole rather than parsed. They carry keys and tokens on some of these
 * vendors, nothing this app needs to group by, and a parser is a thing that can be wrong.
 *
 * ```
 * scrubUrl("https://kamartaj.xyz/api/customers/alvin")
 * // https://kamartaj.xyz/api/customers/{persona}
 *
 * scrubUrl("https://api.elevenlabs.io/v1/text-to-speech/EXAVITQu/stream?output_format=pcm_24000")
 * // https://api.elevenlabs.io/v1/text-to-speech/{voice}/stream
 * ```
 */
fun scrubUrl(url: String): String {
    val withoutQuery = url.substringBefore('?')
    var scrubbed = withoutQuery
    for ((prefix, placeholder) in IDENTIFYING_SEGMENTS) {
        val at = scrubbed.indexOf(prefix)
        if (at == -1) continue
        val start = at + prefix.length
        val end = scrubbed.indexOf('/', start).takeIf { it != -1 } ?: scrubbed.length
        scrubbed = scrubbed.substring(0, start) + placeholder + scrubbed.substring(end)
    }
    return scrubbed
}

/**
 * The path segments that name someone or something specific, and what to call them instead.
 *
 * Deliberately a short, explicit list rather than a rule like "replace anything that looks like an
 * id". A guess about what an id looks like is wrong eventually — in both directions — and a list
 * that has to be extended when an endpoint is added is a list somebody reads while adding it.
 */
private val IDENTIFYING_SEGMENTS = listOf(
    "/api/customers/" to "{persona}",
    "/v1/text-to-speech/" to "{voice}",
)
