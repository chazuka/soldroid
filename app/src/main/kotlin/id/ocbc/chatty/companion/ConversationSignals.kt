package id.ocbc.chatty.companion

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The one thing the notification needs to be able to say to the conversation: stop.
 *
 * # Why this exists
 *
 * A conversation now outlives its window — it has to, or handsfree stops working the moment the
 * screen sleeps. That leaves a way in and no way out: the customer presses home, the agent keeps
 * talking, and the only escape is to find the app again. Something holding a microphone must be
 * stoppable from wherever the customer can see it, which is the notification.
 *
 * The service cannot end a conversation itself — the provider session and its billing live in the
 * view model — so it asks, and the screen that owns the session does the closing. One signal, one
 * direction, no state.
 */
@Singleton
class ConversationSignals @Inject constructor() {

    private val _stop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when something outside the conversation has asked for it to end. */
    val stop: SharedFlow<Unit> = _stop.asSharedFlow()

    /** Asks whoever owns the open conversation to close it. Safe to call from anywhere. */
    fun requestStop() {
        _stop.tryEmit(Unit)
    }
}
