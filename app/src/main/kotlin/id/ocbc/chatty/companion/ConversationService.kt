package id.ocbc.chatty.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import id.ocbc.chatty.MainActivity
import id.ocbc.chatty.R

/**
 * Keeps a conversation alive while the handset is asleep.
 *
 * # Why a service at all
 *
 * Handsfree exists so nobody has to touch the screen, and a screen nobody touches turns itself off —
 * thirty seconds on the handset this was built against. Everything then stops for reasons that have
 * nothing to do with the conversation: Android stops the activity, which used to silence the avatar
 * through its own visibility gate, and an app in the background is not allowed to hold the
 * microphone at all. The customer had said "listen to me" and the phone stopped listening.
 *
 * A foreground service is the platform's answer to exactly this, and it is what a voice call uses.
 * Declaring `microphone` keeps the recogniser legal in the background; `mediaPlayback` keeps the
 * agent audible. The notification is not decoration — it is the price of both, and it is the right
 * price: something listening to a room should say so, and offer a way out.
 *
 * ```
 * ConversationService.start(context, "Daniel")   // while the conversation is open
 * ConversationService.stop(context)              // when the customer leaves it
 * ```
 */
@AndroidEntryPoint
class ConversationService : Service() {

    /** How the notification's stop action reaches the conversation that owns the session. */
    @Inject lateinit var signals: ConversationSignals

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Ask the conversation to close, then go away immediately. Waiting for the app to tear
            // this down would leave the notification — and the microphone — up for as long as the
            // customer stayed out of the app, which is exactly when they pressed the button.
            signals.requestStop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val agent = intent?.getStringExtra(EXTRA_AGENT).orEmpty()

        // The microphone type is claimed only when the microphone has actually been granted.
        //
        // Android refuses a `microphone` foreground service outright unless RECORD_AUDIO is already
        // held — and it refuses by throwing, which crashes the app. On a handset where the permission
        // had been granted during testing this never fired; on a fresh install it fired the moment
        // the customer opened any agent, so the very first run of the app died on the first tap.
        //
        // Playback is always legitimate, so the conversation keeps its audio either way; listening in
        // the background is simply not claimed until there is a microphone to listen with.
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        }

        // Belt as well as braces. The rules for what a foreground service may claim change with every
        // release and differ between manufacturers; none of that is worth a crash on a screen the
        // customer has just opened. Losing the background conversation is a bad day, not a broken app.
        runCatching { startForeground(NOTIFICATION_ID, notification(agent), types) }
            .onFailure { Log.w(TAG, "could not run the conversation in the background", it) }
        // Not sticky: a conversation is a live thing with a session behind it, and resurrecting this
        // after the system killed the app would leave a notification attached to nothing.
        return START_NOT_STICKY
    }

    private fun notification(agent: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.conversation_channel), IMPORTANCE)
                    .apply { setShowBadge(false) },
            )
        }

        val open = android.app.PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        val stop = android.app.PendingIntent.getService(
            this,
            1,
            Intent(this, ConversationService::class.java).setAction(ACTION_STOP),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.conversation_ongoing, agent))
            .setContentText(getString(R.string.conversation_ongoing_detail))
            .setContentIntent(open)
            // The way out. A conversation that can be started from a screen the customer has left
            // must be endable from the only place they can still see it.
            .addAction(0, getString(R.string.conversation_end), stop)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "chatty.conversation"
        private const val CHANNEL_ID = "conversation"
        private const val NOTIFICATION_ID = 1
        private const val IMPORTANCE = NotificationManager.IMPORTANCE_LOW
        private const val EXTRA_AGENT = "agent"
        private const val ACTION_STOP = "id.ocbc.chatty.STOP_CONVERSATION"

        /** Starts the service for [agentName]; safe to call again for the same conversation. */
        fun start(context: Context, agentName: String) {
            val intent = Intent(context, ConversationService::class.java)
                .putExtra(EXTRA_AGENT, agentName)
            context.startForegroundService(intent)
        }

        /** Stops it. The notification goes with it, because the conversation has. */
        fun stop(context: Context) {
            context.stopService(Intent(context, ConversationService::class.java))
        }
    }
}
