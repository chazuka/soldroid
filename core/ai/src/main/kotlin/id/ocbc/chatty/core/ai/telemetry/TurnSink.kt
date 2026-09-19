package id.ocbc.chatty.core.ai.telemetry

import id.ocbc.chatty.core.ai.TurnTrace

/**
 * How a turn ended, which is the first thing you filter by when something looks wrong.
 *
 * Deliberately coarse. The exception and its message go to the crash reporter, where they belong;
 * this is the dimension you group a week of turns by, and five buckets is as fine as that stays
 * useful.
 */
enum class TurnOutcome {
    /** The agent answered and the avatar said it. The only outcome anybody is hoping for. */
    SPOKEN,

    /** The agent answered, but nothing said it out loud — synthesis or the avatar failed. */
    ANSWERED_IN_TEXT,

    /** The customer cut the answer short. Not a fault: a short turn here is the feature working. */
    INTERRUPTED,

    /** The connection failed somewhere. The customer did nothing wrong and a retry may work. */
    FAILED_NETWORK,

    /** A vendor refused, or answered with nothing. */
    FAILED_API,
}

/**
 * One completed turn, as the thing a backend is asked to store.
 *
 * # What is deliberately not here
 *
 * The question, the answer, and any field of the customer's record. This type is the boundary
 * between the app and whoever hosts the telemetry, so the rule is enforced by what the type can
 * carry rather than by everyone remembering: durations, counts and enums only. Adding a `String`
 * for "what they asked" would compile, which is exactly why the reason it must not exist is written
 * down here.
 *
 * Everything in [trace] is milliseconds relative to the moment the question was asked, so the
 * numbers stay readable and no wall clock travels with them.
 */
data class TurnEvent(
    val trace: TurnTrace,

    /** Which stack answered — the comparison the whole app exists to run. */
    val brain: String,

    /** The persona, which is a demo character rather than a person. */
    val agent: String,

    /** The language the answer was spoken in. */
    val language: String,

    /** Whether the microphone re-armed itself, because handsfree turns carry the endpointing wait. */
    val handsfree: Boolean,

    /**
     * Whether the brief was already in hand when the turn started.
     *
     * False means the customer asked faster than the warm-up finished and paid for the fetch. It is
     * the only way to tell whether that optimisation is doing anything in the field, rather than
     * only on the bench where it was measured.
     */
    val warmHit: Boolean,

    val outcome: TurnOutcome,
)

/**
 * Where completed turns go.
 *
 * # Why a port rather than calling an SDK
 *
 * Which backend receives this is a procurement decision and a reversible one, so it does not belong
 * in the conversation code. The app records a [TurnEvent]; an adapter decides whether that becomes a
 * Sentry transaction, a line in logcat, or nothing at all. Swapping vendors is writing one class and
 * changing one line of dependency injection — no caller changes, and nothing about a vendor's SDK
 * reaches [id.ocbc.chatty.companion.CompanionViewModel].
 *
 * It also keeps this module honest: `core:ai` is plain JVM and stays that way, so the rules about
 * what may be sent are unit-testable without an emulator.
 *
 * # The contract every adapter owes
 *
 * [record] must not block, must not throw, and must never add latency to the turn it is describing.
 * It is called on a scope that outlives the screen, after the turn has already ended, and an
 * adapter that talks to the network is expected to hand off to its own queue rather than send
 * inline. Telemetry that slows down the thing it measures is worse than no telemetry.
 *
 * ```
 * class LogTurnSink : TurnSink {
 *     override fun record(turn: TurnEvent) = Log.i("chatty.turn", turn.trace.summary())
 * }
 * ```
 */
interface TurnSink {
    /**
     * Notes that a turn has started, before any work is done for it.
     *
     * # Why a sink needs to know this and not just the result
     *
     * Some backends time a unit of work by watching it happen rather than by being told about it
     * afterwards. Sentry is one: its OkHttp integration produces a span for every DNS lookup, TLS
     * handshake and response body on this app's four vendors — for free, and *only* onto a
     * transaction that is live while the call is made. A sink told solely about finished turns
     * would throw that away and be left with the handful of numbers the app measured itself.
     *
     * Default is to do nothing, because a sink that only writes a line at the end has nothing to
     * open. Callers must treat it as advisory: it is not a resource to be closed, and a turn that
     * begins and never records is a turn that failed, not a leak.
     */
    fun begin() = Unit

    /** Records one completed turn. Never throws; never blocks. */
    fun record(turn: TurnEvent)
}

/**
 * The sink for a build with no backend configured.
 *
 * Not a special case anyone has to branch on: a build without a DSN gets this, and every caller goes
 * on recording turns into it. "Telemetry is off" and "telemetry is on" differ by which object is
 * injected, which means the path that runs in a demo is the same path that ran in testing.
 */
object NoTurnSink : TurnSink {
    override fun record(turn: TurnEvent) = Unit
}
