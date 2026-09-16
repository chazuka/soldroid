package id.ocbc.chatty.core.avatar

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.NoAudioHandler
import io.livekit.android.events.RoomEvent
// `Room.events` is an EventListenable, not a Flow; its `collect` is an extension.
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import livekit.org.webrtc.EglBase
import livekit.org.webrtc.RendererCommon
import io.livekit.android.room.Room
import io.livekit.android.room.track.AudioTrack
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the companion screen needs to know about the face. */
sealed interface AvatarEvent {
    /** The room is joined and the avatar's video track is subscribed. */
    data object Attached : AvatarEvent

    /** The stream died. [reason] is a short, human-readable cause for logging or a notice. */
    data class Failed(val reason: String) : AvatarEvent
}

/**
 * The face, as the rest of the app sees it.
 *
 * An interface because everything below it — LiveKit, EGL, native track disposal — is untestable on
 * the JVM, and because the companion screen must stay demonstrable with the avatar switched off.
 */
interface AvatarController {
    /** Fires once per state change the companion screen needs to react to — see [AvatarEvent]. */
    val events: SharedFlow<AvatarEvent>

    /** Null whenever there is no live remote video: before attach, during re-create, after failure. */
    val videoTrack: StateFlow<VideoTrack?>

    /** True while the avatar's voice is silenced on this device. */
    val audioMuted: StateFlow<Boolean>

    /**
     * Silence (or restore) the avatar's voice on this device only.
     *
     * Local playout mute, not barge-in: the utterance keeps streaming and the end-of-speech watchdog
     * still fires, because it listens to the room's active-speaker events rather than to playback.
     * The mute is utterance-scoped — it clears when the current speech finishes.
     */
    fun setAudioMuted(muted: Boolean)

    /**
     * Silence the avatar because the customer is on a surface meant to be *read*.
     *
     * Text mode shows the whole conversation as a thread; an answer arriving out loud over it is
     * startling, and on a phone in public it is worse than startling. Independent of
     * [setAudioMuted] and of app visibility — any one of the three silences playout — so entering
     * and leaving text mode never clobbers a mute the customer set deliberately.
     */
    fun setPlaybackSuppressed(suppressed: Boolean)

    /**
     * Prepare a renderer against this controller's EGL context, optionally with a custom [drawer].
     *
     * A `TextureViewRenderer`, not the `SurfaceViewRenderer` LiveKit's samples reach for first. A
     * `SurfaceView` is its own window punched through the view hierarchy, so Compose cannot clip it —
     * rounding it into a circle silently does nothing and the video keeps its square corners. A
     * `TextureView` composites like any other view and clips normally, which is what the stage's
     * circular portrait needs.
     *
     * LiveKit owns the EGL context, so a renderer cannot initialise itself — calling `init()`
     * directly logs an error and draws nothing. Passing a drawer is how the chroma-key shader gets
     * in front of the frames; passing null keeps LiveKit's own default.
     *
     * [onFrameSize] reports the decoded frame's dimensions and the rotation to apply, the first time
     * they are known and again whenever they change. It is the only place the stream says what shape
     * it is; without it a caller has to assume, and an assumption about someone else's encoder is a
     * thing that is right until the day it is not. Fires on the renderer thread.
     */
    fun initRenderer(
        renderer: TextureViewRenderer,
        drawer: RendererCommon.GlDrawer? = null,
        onFirstFrame: (() -> Unit)? = null,
        onFrameSize: ((width: Int, height: Int, rotation: Int) -> Unit)? = null,
    )

    /** Join the room LiveAvatar published into. Safe to call again with a fresh stream. */
    fun attach(stream: AvatarStream)

    /**
     * Take audio focus for an utterance that is about to play.
     *
     * Paired with [endUtterance]. The pair exists because *when* speech ends is the provider's fact,
     * reported on its own socket, not something this class can observe from the media stream — so
     * the caller drives the boundaries and this class only owns what the boundaries mean for audio.
     */
    fun beginUtterance()

    /** Give the audio output back, and clear an utterance-scoped mute. */
    fun endUtterance()

    /** Leave the current room without disposing this controller. Safe to call when already detached. */
    fun detach()

    /** Tear this controller down for good: [detach], then release the room and its EGL context. */
    fun release()
}

/**
 * The real implementation: LiveKit.
 *
 * LiveAvatar renders the talking head server-side and publishes it into a LiveKit room; [open] on
 * [LiveAvatarSession] hands back the room URL and a short-lived token. Subscribing is this class's
 * whole job — signalling, ICE and renegotiation are the SDK's problem, which is the right place for
 * them, because they are transport and this module has no opinion about transport.
 */
class LiveKitAvatarController(
    private val context: Context,
    private val scope: CoroutineScope,
) : AvatarController {

    private val _events = MutableSharedFlow<AvatarEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: SharedFlow<AvatarEvent> = _events.asSharedFlow()

    private val _videoTrack = MutableStateFlow<VideoTrack?>(null)
    override val videoTrack: StateFlow<VideoTrack?> = _videoTrack.asStateFlow()

    private val _audioMuted = MutableStateFlow(false)
    override val audioMuted: StateFlow<Boolean> = _audioMuted.asStateFlow()

    // The avatar's remote audio, held only to gate local playout. The room subscribes regardless;
    // muting flips the underlying rtc track's enabled flag, which stops playout without touching
    // the subscription or the utterance.
    private var audioTrack: AudioTrack? = null

    // Three independent reasons to silence playout; any one suffices. Only the customer's tap is
    // surfaced as state — the other two are circumstances, not choices.
    @Volatile private var focusLost = false
    @Volatile private var playbackSuppressed = false

    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Any of the three loss variants silences playout; this app has no partial-duck behaviour to fall
    // back to, so "duck" is treated the same as a full loss rather than lowering volume.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        focusLost = when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
            -> true
            AudioManager.AUDIOFOCUS_GAIN -> false
            else -> focusLost
        }
        applyAudioMute()
    }

    // Transient-may-duck: the avatar speaks in bursts, and other audio should return when the
    // utterance ends. Declared as assistant speech so the system ducks others sensibly.
    private val focusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener)
            .build()

    // One Room at a time. It owns the EGL context and, transitively, one native
    // PeerConnectionFactory; two live factories in a process is a native crash on dispose.
    private var room: Room = createRoom()
    private var released = false

    private var eventsJob: Job? = null
    private var connectJob: Job? = null

    init {
        subscribeRoomEvents()
    }

    /**
     * Builds the room as a *listener*, not as a call participant.
     *
     * LiveKit's defaults assume both directions: it puts the device into communication mode, takes
     * `USAGE_VOICE_COMMUNICATION` focus, routes to the earpiece, and prewarms an `AudioRecord` so
     * echo cancellation has a reference. On a phone that shows up as the system's microphone
     * indicator sitting lit over an app that never records, and as an avatar talking quietly out of
     * the earpiece instead of the speaker.
     *
     * None of it applies here. The customer's voice is transcribed on-device and sent as text; this
     * room only ever subscribes. So the audio output is declared as media, the device-routing
     * handler is removed, and the microphone prewarm is turned off.
     */
    private fun createRoom(): Room = LiveKit.create(
        appContext = context.applicationContext,
        options = RoomOptions(adaptiveStream = true, dynacast = true),
        overrides = LiveKitOverrides(
            audioOptions = AudioOptions(
                // MODE_NORMAL, USAGE_MEDIA, STREAM_MUSIC: the avatar plays out of the speaker at
                // media volume, like every other voice on the phone that is not a phone call.
                audioOutputType = AudioType.MediaAudioType(),
                // Nothing to route: there is no call to move between earpiece, speaker and headset.
                audioHandler = NoAudioHandler(),
                // The one that actually lights the privacy indicator.
                disableAudioPrewarming = true,
            ),
        ),
    )

    private fun subscribeRoomEvents() {
        eventsJob?.cancel()
        eventsJob = scope.launch { room.events.collect(::onRoomEvent) }
    }

    override fun initRenderer(
        renderer: TextureViewRenderer,
        drawer: RendererCommon.GlDrawer?,
        onFirstFrame: (() -> Unit)?,
        onFrameSize: ((width: Int, height: Int, rotation: Int) -> Unit)?,
    ) {
        if (drawer == null) {
            room.initVideoRenderer(renderer)
            return
        }
        // The same thing `initVideoRenderer` does, with our shader substituted for the stock one and
        // a first-frame callback so the caller can hold a placeholder until there is really a
        // picture — a TextureView is opaque black until then, and swapping to it early shows that.
        val events = if (onFirstFrame == null && onFrameSize == null) {
            null
        } else {
            object : RendererCommon.RendererEvents {
                override fun onFirstFrameRendered() {
                    onFirstFrame?.invoke()
                }

                override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                    onFrameSize?.invoke(width, height, rotation)
                }
            }
        }
        renderer.init(room.lkObjects.eglBase.eglBaseContext, events, EglBase.CONFIG_PLAIN, drawer)
        renderer.setEnableHardwareScaler(false)
    }

    override fun attach(stream: AvatarStream) {
        if (released) {
            // A prior release() disposed the room terminally. Stand up a fresh one so a reused
            // instance — a cached process relaunching after the Activity finished — still works.
            room = createRoom()
            subscribeRoomEvents()
            released = false
        } else {
            // LiveKit permits one connection per Room, and a stale subscription keeps decoding into
            // a renderer nobody is showing.
            detach()
        }

        connectJob = scope.launch {
            try {
                room.connect(url = stream.livekitUrl, token = stream.livekitToken)
            } catch (e: Exception) {
                // The face must not freeze on the last decoded frame.
                _videoTrack.value = null
                _events.tryEmit(AvatarEvent.Failed(e.message ?: "connect failed"))
            }
        }
    }

    /**
     * Turns LiveKit's room-level events into the state and [AvatarEvent]s this controller exposes.
     *
     * A subscribed video track is the face becoming visible, so only that branch reports
     * [AvatarEvent.Attached]; a subscribed audio track is just captured for [applyAudioMute] to gate
     * later and raises nothing on its own. [RoomEvent.Disconnected] only reports [AvatarEvent.Failed]
     * when it carries an error — a disconnect this class asked for (via [detach]) clears the video
     * track itself and leaves the error null, which is how the two cases are told apart without extra
     * state.
     */
    private fun onRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.TrackSubscribed -> when (val track = event.track) {
                is VideoTrack -> {
                    _videoTrack.value = track
                    _events.tryEmit(AvatarEvent.Attached)
                }
                is AudioTrack -> {
                    audioTrack = track
                    applyAudioMute()
                }
                else -> Unit
            }

            is RoomEvent.TrackUnsubscribed -> {
                if (event.track === _videoTrack.value) _videoTrack.value = null
                if (event.track === audioTrack) audioTrack = null
            }

            is RoomEvent.Disconnected -> {
                _videoTrack.value = null
                // A disconnect we asked for is not a failure; `detach()` nulls the track first.
                event.error?.let { _events.tryEmit(AvatarEvent.Failed(it.message ?: "disconnected")) }
            }

            is RoomEvent.FailedToConnect -> {
                _videoTrack.value = null
                _events.tryEmit(AvatarEvent.Failed(event.error.message ?: "failed to connect"))
            }

            else -> Unit
        }
    }

    override fun beginUtterance() {
        // Transient-may-duck focus for the length of the answer; other audio returns afterwards.
        audioManager.requestAudioFocus(focusRequest)
    }

    override fun endUtterance() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        focusLost = false
        // The mute is scoped to the utterance it silenced; the next answer starts audible.
        if (_audioMuted.value) setAudioMuted(false) else applyAudioMute()
    }

    override fun setAudioMuted(muted: Boolean) {
        _audioMuted.value = muted
        applyAudioMute()
    }

    override fun setPlaybackSuppressed(suppressed: Boolean) {
        playbackSuppressed = suppressed
        applyAudioMute()
    }

    private fun applyAudioMute() {
        // setEnabled on a remote audio track gates playout locally; the subscription and the
        // provider's speak lifecycle are untouched.
        //
        // [appVisible] is deliberately *not* one of the reasons any more. A conversation outlives the
        // screen — handsfree exists so nobody touches the handset, and a handset nobody touches goes
        // dark within half a minute — so silencing on the activity stopping meant the agent went
        // quiet mid-sentence exactly when the customer was relying on it most. A foreground service
        // keeps the conversation legal and audible while the screen is off, the way a call does; the
        // customer's own mute and a genuine audio-focus loss still silence it, and leaving the
        // conversation closes the session outright.
        audioTrack?.rtcTrack?.setEnabled(
            !(_audioMuted.value || focusLost || playbackSuppressed),
        )
    }

    override fun detach() {
        connectJob?.cancel(); connectJob = null
        audioManager.abandonAudioFocusRequest(focusRequest)
        focusLost = false
        _videoTrack.value = null
        audioTrack = null
        _audioMuted.value = false
        // Guarded: after a terminal release() the room is disposed, and touching it would fault.
        if (!released) room.disconnect()
    }

    override fun release() {
        if (released) return
        detach()
        eventsJob?.cancel(); eventsJob = null
        room.release()
        released = true
    }

}
