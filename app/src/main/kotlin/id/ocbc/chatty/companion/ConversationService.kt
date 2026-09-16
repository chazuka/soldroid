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
import id.ocbc.chatty.core.ai.Language
import id.ocbc.chatty.localizedFor

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
        val language = intent?.getStringExtra(EXTRA_LANGUAGE)
            ?.let { tag -> Language.entries.firstOrNull { it.tag == tag } }
            ?: Language.INDONESIAN

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
        runCatching { startForeground(NOTIFICATION_ID, notification(agent, language), types) }
            .onFailure { Log.w(TAG, "could not run the conversation in the background", it) }
        // Not sticky: a conversation is a live thing with a session behind it, and resurrecting this
        // after the system killed the app would leave a notification attached to nothing.
        return START_NOT_STICKY
    }

    /**
     * [language] is carried in rather than read from the handset.
     *
     * A Service has no composition, so `ProvideAppLanguage` cannot reach it — and this notification
     * is the one piece of the app a customer reads while they are somewhere else entirely. Left to
     * `getString` it would be written in whatever Android is set to, which is how a conversation
     * held in English ends up announcing itself in Indonesian on the lock screen.
     */
    private fun notification(agent: String, language: Language): Notification {
        val words = localizedFor(language)
        val manager = getSystemService(NotificationManager::class.java)
        // No version guard: the app's minimum is 29, so channels always exist.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, words.getString(R.string.conversation_channel), IMPORTANCE)
                .apply { setShowBadge(false) },
        )

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
            .setContentTitle(words.getString(R.string.conversation_ongoing, agent))
            .setContentText(words.getString(R.string.conversation_ongoing_detail))
            .setContentIntent(open)
            // The way out. A conversation that can be started from a screen the customer has left
            // must be endable from the only place they can still see it.
            .addAction(0, words.getString(R.string.conversation_end), stop)
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
        private const val EXTRA_LANGUAGE = "language"
        private const val ACTION_STOP = "id.ocbc.chatty.STOP_CONVERSATION"

        /**
         * Starts the service for [agentName], in [language]; safe to call again for the same
         * conversation — and it is called again when the language changes, so the notification is
         * rewritten rather than left in the language the conversation happened to open in.
         */
        fun start(context: Context, agentName: String, language: Language) {
            val intent = Intent(context, ConversationService::class.java)
                .putExtra(EXTRA_AGENT, agentName)
                .putExtra(EXTRA_LANGUAGE, language.tag)
            context.startForegroundService(intent)
        }

        /** Stops it. The notification goes with it, because the conversation has. */
        fun stop(context: Context) {
            context.stopService(Intent(context, ConversationService::class.java))
        }
    }
}
