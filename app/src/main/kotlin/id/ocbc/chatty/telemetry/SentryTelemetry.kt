package id.ocbc.chatty.telemetry

import android.content.Context
import id.ocbc.chatty.core.ai.telemetry.TelemetryPolicy
import id.ocbc.chatty.core.ai.telemetry.TurnEvent
import id.ocbc.chatty.core.ai.telemetry.TurnSink
import id.ocbc.chatty.core.ai.telemetry.scrubUrl
import io.sentry.HttpStatusCodeRange
import io.sentry.ISpan
import io.sentry.ITransaction
import io.sentry.ScopesAdapter
import io.sentry.Sentry
import io.sentry.SpanStatus
import io.sentry.TransactionOptions
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.Device
import io.sentry.protocol.OperatingSystem
import io.sentry.okhttp.SentryOkHttpInterceptor

/**
 * Starts Sentry, and is the only place in this app that configures it.
 *
 * # Why the SDK is never configured anywhere else
 *
 * Every switch here decides whether something about a customer leaves the handset, and several of
 * them default to *on* in the SDK — failed-request capture, which carries response bodies, above
 * all. Spread across the codebase those decisions cannot be reviewed; gathered here they are one
 * diff, and [TelemetryPolicy] is the page that explains each one. If you are adding an option, add
 * it to the policy first and apply it here second.
 *
 * ```
 * SentryTelemetry.start(context, BuildConfig.SENTRY_DSN, TelemetryPolicy.Default)
 * ```
 */
object SentryTelemetry {

    /**
     * Brings Sentry up against [policy], or does nothing at all when [dsn] is blank.
     *
     * A blank DSN is the ordinary state of a developer's build and of any build nobody has
     * configured a backend for, so it is a supported configuration rather than an error: the app
     * runs, turns are still recorded to logcat by [LogTurnSink], and nothing is sent.
     */
    fun start(context: Context, dsn: String, policy: TelemetryPolicy, debug: Boolean = false) {
        if (dsn.isBlank() || policy.recordsNothing) return

        SentryAndroid.init(context) { options ->
            options.dsn = dsn
            // Whether anything is actually reaching Sentry is otherwise unanswerable from the
            // handset: the SDK is silent by default, so a misconfigured DSN and a working one look
            // identical in logcat. On in debug builds only — it is noisy, and a release build has
            // nobody reading its log.
            options.isDebug = debug
            options.tracesSampleRate = policy.sampleRate

            // Both would attach the open transcript — the figures, on screen — to an error report.
            options.isAttachScreenshot = policy.attachScreenshot
            options.isAttachViewHierarchy = policy.attachViewHierarchy

            // IP, headers, cookies, request bodies. None of it is anything this app needs sent.
            options.isSendDefaultPii = policy.sendDefaultPii

            if (!policy.deviceContext) {
                // Stripped on the way out rather than switched off at the source.
                //
                // Which SDK toggle suppresses which piece of device information is a moving target
                // across releases, and a policy that silently stops being enforced by an upgrade is
                // worse than one that was never written. Removing the contexts from the event itself
                // is the one implementation that cannot drift: whatever gathered them, they do not
                // leave. Both hooks, because transactions carry contexts too and do not pass
                // through `beforeSend`.
                options.setBeforeSend { event, _ ->
                    event.also {
                        it.contexts.remove(Device.TYPE)
                        it.contexts.remove(OperatingSystem.TYPE)
                    }
                }
                options.setBeforeSendTransaction { transaction, _ ->
                    transaction.also {
                        it.contexts.remove(Device.TYPE)
                        it.contexts.remove(OperatingSystem.TYPE)
                    }
                }
            }

            if (policy.scrubUrls) {
                // Spans carry the address a second time, in their data map, and under whichever key
                // the integration chose. Rewriting named keys is what failed here: "url" was
                // handled, "path" was not, and the raw voice id shipped while the description
                // beside it read {voice}. So every string value is swept instead — a value that
                // contains no identifying segment comes back unchanged, and a key nobody
                // anticipated is covered anyway.
                options.setBeforeSendTransaction { transaction, _ ->
                    transaction.also {
                        it.spans.forEach { span ->
                            span.data?.let { data ->
                                span.data = data.mapValues { (_, value) ->
                                    if (value is String) scrubUrl(value) else value
                                }
                            }
                        }
                    }
                }
            }

            if (!policy.httpBreadcrumbs) {
                options.setBeforeBreadcrumb { breadcrumb, _ ->
                    if (breadcrumb.type == BREADCRUMB_HTTP) null else breadcrumb
                }
            } else if (policy.scrubUrls) {
                options.setBeforeBreadcrumb { breadcrumb, _ ->
                    breadcrumb.also { crumb ->
                        // Both keys, because the SDK uses whichever suits the integration and
                        // guessing wrong is silent. Verified on a device: OkHttp breadcrumbs put
                        // the address under "path", so scrubbing only "url" shipped the voice id
                        // in full while the spans beside it were correctly rewritten.
                        for (key in BREADCRUMB_ADDRESS_KEYS) {
                            (crumb.data[key] as? String)?.let { crumb.data[key] = scrubUrl(it) }
                        }
                    }
                }
            }

        }
    }

    /**
     * The OkHttp interceptor, built to [policy].
     *
     * # Why failed-request capture is set here and not with the rest of the options
     *
     * Because as of SDK 8 it lives on the interceptor rather than on `SentryOptions`, and it is the
     * setting that decides whether a failed `/api/customers/{persona}` call ships its **response
     * body** — the customer's balances — to the backend. It follows [TelemetryPolicy] like
     * everything else; it just has to be passed in at construction.
     *
     * The callback scrubs each span's URL as the span is made, which is the only hook that can:
     * by the time a transaction reaches `beforeSendTransaction` its spans are read-only.
     */
    fun okHttpInterceptor(policy: TelemetryPolicy): SentryOkHttpInterceptor = SentryOkHttpInterceptor(
        ScopesAdapter.getInstance(),
        SentryOkHttpInterceptor.BeforeSpanCallback { span, _, _ ->
            if (policy.scrubUrls) span.description?.let { span.description = scrubUrl(it) }
            span
        },
        policy.captureFailedRequests,
        listOf(HttpStatusCodeRange(SERVER_ERROR_FROM, SERVER_ERROR_TO)),
        listOf(ANY_TARGET),
    )

    private const val BREADCRUMB_HTTP = "http"

    /** Every key an HTTP breadcrumb may carry an address under. */
    private val BREADCRUMB_ADDRESS_KEYS = listOf("url", "path")
    private const val SERVER_ERROR_FROM = 500
    private const val SERVER_ERROR_TO = 599
    private const val ANY_TARGET = ".*"
}

/**
 * [TurnSink] that records a turn as one Sentry transaction with a span per leg.
 *
 * # What you get that the app did not measure itself
 *
 * The transaction is opened at the start of the turn and bound to the scope, which is what makes
 * Sentry's OkHttp integration attach *its* spans to it — one per DNS lookup, TLS handshake, request
 * and response body, across all four vendors. So the waterfall answers "was that nine seconds the
 * model or the 4G handshake" without this app timing a single socket.
 *
 * The legs this app does measure are added on top as child spans, reconstructed from the trace's
 * own offsets once the turn has ended. They are not live timings — nothing here holds a span open
 * across the pipeline — which means their boundaries are exact and their nesting is honest, but a
 * leg the trace never marked simply does not appear.
 *
 * # What is sent
 *
 * Durations, counts and enums. [TurnEvent] cannot carry the question, the answer, or the record,
 * and that is deliberate — see the note on the type.
 */
class SentryTurnSink : TurnSink {

    /**
     * The turn in flight, or null between turns.
     *
     * Volatile because it is written on the turn's coroutine and read by whatever thread the sink
     * is finished on. A turn that begins and never records leaves this set until the next [begin]
     * replaces it, which is the failure mode worth knowing about and a cheap one: one abandoned
     * transaction, never sent.
     */
    @Volatile private var transaction: ITransaction? = null

    override fun begin() {
        // Bound to scope so the OkHttp integration's spans find it. Without this the network
        // breakdown lands on no transaction at all and is silently dropped.
        transaction = Sentry.startTransaction(
            TRANSACTION_NAME,
            OP_TURN,
            TransactionOptions().apply { isBindToScope = true },
        )
    }

    override fun record(turn: TurnEvent) {
        val open = transaction ?: return
        transaction = null

        open.setTag("brain", turn.brain)
        open.setTag("agent", turn.agent)
        open.setTag("language", turn.language)
        open.setTag("voice", turn.voice)
        open.setTag("handsfree", turn.handsfree.toString())
        open.setTag("warm_hit", turn.warmHit.toString())
        open.setTag("speculated", turn.speculated)
        open.setTag("connection", turn.connection)
        open.setTag("outcome", turn.outcome.name)
        open.setTag("starved", turn.trace.starved.toString())

        val trace = turn.trace
        trace.listenedMs?.let { open.setMeasurement("listened_ms", it) }
        trace.firstTokenMs?.let { open.setMeasurement("first_token_ms", it) }
        trace.firstAudioMs?.let { open.setMeasurement("first_audio_ms", it) }
        trace.speakStartedMs?.let { open.setMeasurement("lips_moved_ms", it) }
        // The legs in isolation, so a chart does not have to subtract two columns to ask "was that
        // the synthesizer or the renderer". See TurnTrace for why they are derived rather than marked.
        trace.clauseMs?.let { open.setMeasurement("clause_ms", it) }
        trace.ttsMs?.let { open.setMeasurement("tts_ms", it) }
        trace.avatarMs?.let { open.setMeasurement("avatar_ms", it) }
        open.setMeasurement("answer_chars", turn.answerChars)
        open.setMeasurement("sentences", trace.sentences)
        open.setMeasurement("starved", trace.starved)
        open.setMeasurement("reconnects", trace.reconnects)

        // The legs, as spans, so the transaction reads as a waterfall rather than a bag of numbers.
        open.leg(OP_LLM, from = 0, to = trace.firstTokenMs)
        open.leg(OP_LLM_REST, from = trace.firstTokenMs, to = trace.answerCompleteMs)
        open.leg(OP_TTS, from = trace.firstTokenMs, to = trace.firstAudioMs)
        open.leg(OP_AVATAR, from = trace.firstAudioMs, to = trace.speakStartedMs)
        open.leg(OP_SPEAKING, from = trace.speakStartedMs, to = trace.speakEndedMs)

        open.finish(turn.outcome.status())
    }

    /**
     * Adds one child span covering a leg, or nothing when either end of it was never marked.
     *
     * The offsets are milliseconds from the question, so a leg is described rather than timed: the
     * span carries its own boundaries in its description, which is what a backend can group and
     * compare. A leg with a missing end is a leg that did not happen — a turn that failed before
     * the avatar spoke has no speaking span, and inventing a zero-length one would read as though
     * it had.
     */
    private fun ISpan.leg(operation: String, from: Long?, to: Long?) {
        if (from == null || to == null || to < from) return
        startChild(operation, "${to - from}ms").finish()
    }

    private fun id.ocbc.chatty.core.ai.telemetry.TurnOutcome.status(): SpanStatus = when (this) {
        id.ocbc.chatty.core.ai.telemetry.TurnOutcome.SPOKEN -> SpanStatus.OK
        id.ocbc.chatty.core.ai.telemetry.TurnOutcome.ANSWERED_IN_TEXT -> SpanStatus.OK
        id.ocbc.chatty.core.ai.telemetry.TurnOutcome.INTERRUPTED -> SpanStatus.CANCELLED
        id.ocbc.chatty.core.ai.telemetry.TurnOutcome.FAILED_NETWORK -> SpanStatus.UNAVAILABLE
        id.ocbc.chatty.core.ai.telemetry.TurnOutcome.FAILED_API -> SpanStatus.INTERNAL_ERROR
    }

    private companion object {
        const val TRANSACTION_NAME = "turn"
        const val OP_TURN = "companion.turn"
        const val OP_LLM = "llm.first_token"
        const val OP_LLM_REST = "llm.generate"
        const val OP_TTS = "tts.first_audio"
        const val OP_AVATAR = "avatar.to_lips"
        const val OP_SPEAKING = "avatar.speaking"
    }
}
