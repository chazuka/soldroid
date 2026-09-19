package id.ocbc.chatty

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import id.ocbc.chatty.core.ai.telemetry.TelemetryPolicy
import id.ocbc.chatty.telemetry.SentryTelemetry

/**
 * The process entry point. Hilt needs it annotated; almost nothing else belongs here.
 *
 * In particular the LiveKit room is *not* created at startup. It is a native peer-connection factory
 * and an EGL context, and paying for both on a cold launch would delay the first screen — which is a
 * list, and does not need a face.
 *
 * Telemetry is the one exception, and only because it has to be: a crash reporter that starts after
 * the thing that crashed is a crash reporter that missed it. It is cheap when configured and free
 * when not — a blank DSN returns immediately, which is the state of every build until someone
 * configures a backend.
 */
@HiltAndroidApp
class ChattyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SentryTelemetry.start(this, BuildConfig.SENTRY_DSN, TelemetryPolicy.Default)
    }
}
