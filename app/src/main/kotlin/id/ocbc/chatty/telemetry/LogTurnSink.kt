package id.ocbc.chatty.telemetry

import android.util.Log
import id.ocbc.chatty.core.ai.telemetry.TurnEvent
import id.ocbc.chatty.core.ai.telemetry.TurnSink

/**
 * The adapter for a build with no backend: turns go to logcat and no further.
 *
 * # Why this is the default rather than [id.ocbc.chatty.core.ai.telemetry.NoTurnSink]
 *
 * A developer on their own handset wants the numbers, and wants them now, without an account or a
 * DSN or a network round trip. This is also what makes the telemetry path *testable in a demo*: the
 * same [TurnSink] call site runs whether a backend is configured or not, so "it stopped recording
 * when we turned Sentry on" cannot be a surprise discovered in front of a customer.
 *
 * Nothing sensitive is written, for the same reason nothing sensitive is sent — [TurnEvent] cannot
 * carry it. Logcat is readable by anyone with the handset plugged in, which is exactly the
 * constraint that shaped what the event is allowed to hold.
 *
 * ```
 * chatty.turn: turn ANTHROPIC/SPOKEN heard 1620ms  llm 1071ms/1584ms  tts 1839ms  lips 2140ms/19204ms  2 sentence(s)
 * ```
 */
class LogTurnSink : TurnSink {
    override fun record(turn: TurnEvent) {
        Log.i(TAG, "turn ${turn.brain}/${turn.outcome} ${turn.trace.summary()}")
    }

    private companion object {
        const val TAG = "chatty.turn"
    }
}
