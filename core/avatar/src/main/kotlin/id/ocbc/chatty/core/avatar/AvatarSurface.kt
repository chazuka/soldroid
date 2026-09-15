package id.ocbc.chatty.core.avatar

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import livekit.org.webrtc.RendererCommon
import livekit.org.webrtc.chromaKeyDrawer

/**
 * The live video sink, held for as long as the conversation rather than for as long as the stage.
 *
 * # Why this is not just a local in [AvatarSurface]
 *
 * The renderer used to be remembered inside the surface, which meant opening the text thread
 * released it and coming back built a new one. A new `TextureView` has no picture until the decoder
 * hands it a keyframe, and nothing asks the provider for one — so the customer tapped "show face"
 * and looked at a photograph for a measured 3.7 seconds.
 *
 * Held here, the sink survives the switch: frames keep arriving into a view that is simply not on
 * screen, and coming back is a re-parent rather than a reconnect. Decoding was happening either way
 * — the track stays subscribed while the thread is open, because the conversation underneath it is
 * still live — so this costs a composite, not a decode.
 *
 * Create it above every surface that might show the face, and pass it down:
 *
 * ```
 * val face = rememberAvatarRenderTarget(controller, background = Color.Black)
 * if (onStage) VoiceStage(face = face, …) else TextThread(…)   // face survives the else branch
 * ```
 */
@Stable
class AvatarRenderTarget internal constructor(internal val renderer: TextureViewRenderer) {
    /**
     * Whether a frame has actually been decoded into [renderer].
     *
     * A `TextureView` is opaque black between being attached and its first frame, so the placeholder
     * has to stay on top until this is true or the face flashes black. Reset whenever the track is
     * replaced, because that wait starts again.
     */
    internal var hasFrame by mutableStateOf(false)
}

/**
 * Builds an [AvatarRenderTarget] bound to [controller], keyed on nothing but the composition it sits
 * in — put it somewhere that outlives the individual screens, or it defeats its own purpose.
 *
 * [background] is the colour the chroma key composites onto, and should match whatever the face will
 * be drawn against.
 */
@Composable
fun rememberAvatarRenderTarget(controller: AvatarController, background: Color): AvatarRenderTarget {
    val context = LocalContext.current
    val target = remember { AvatarRenderTarget(TextureViewRenderer(context)) }
    val backgroundArgb = background.toArgb()

    // LiveKit owns the EGL context, so the room initialises the renderer; calling
    // `renderer.init(...)` here would log an error and draw nothing.
    DisposableEffect(target, backgroundArgb) {
        val readyAtMs = System.currentTimeMillis()
        controller.initRenderer(
            renderer = target.renderer,
            drawer = chromaKeyDrawer(backgroundArgb),
            onFirstFrame = {
                Log.i(TAG, "first frame ${System.currentTimeMillis() - readyAtMs}ms after the sink opened")
                target.hasFrame = true
            },
        )
        target.renderer.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        onDispose { target.renderer.release() }
    }

    val track by controller.videoTrack.collectAsState()

    // Keyed on the track: a re-attach produces a new one, and the old sink must come off or
    // libwebrtc keeps decoding into a renderer nobody is showing.
    DisposableEffect(track, target) {
        val live = track
        live?.addRenderer(target.renderer)
        onDispose {
            live?.removeRenderer(target.renderer)
            target.hasFrame = false
        }
    }

    return target
}

/**
 * The avatar's video surface, with its green backdrop replaced by [background].
 *
 * # What this does to the picture
 *
 * LiveAvatar publishes the head against chroma-key green. A [chromaKeyDrawer] keys that out in the
 * fragment shader and composites onto [background] — pass the same colour the surrounding stage is
 * painted, and the face reads as standing on the app's own ground rather than in a green box.
 *
 * Scaling is aspect-**fit**, not fill. Filling would crop a portrait frame to the view's shape and
 * take the top of the head with it; fitting shows the whole frame and lets the letterbox disappear,
 * because the bars are the same colour the shader just painted behind the subject.
 *
 * The renderer is a `TextureView`, which matters when the caller rounds this into a circle: a
 * `SurfaceView` is a separate window and ignores Compose's `clip`, so the corners would stay square.
 *
 * [idle] draws whenever there is no picture — before the first frame, and after a failure. The face
 * never freezes on a stale frame: [AvatarController] drops the track rather than leaving the last
 * one attached.
 *
 * ```
 * AvatarSurface(
 *     target = rememberAvatarRenderTarget(controller, StageColors.base),
 *     background = StageColors.base,
 *     idle = { AgentPortrait(agent) },
 *     modifier = Modifier.fillMaxSize(),
 * )
 * ```
 */
@Composable
fun AvatarSurface(
    target: AvatarRenderTarget,
    background: Color,
    modifier: Modifier = Modifier,
    idle: @Composable () -> Unit = {},
) {
    Box(modifier = modifier.background(background), contentAlignment = Alignment.Center) {
        AndroidView(factory = { target.renderer }, modifier = Modifier.fillMaxSize())

        // Drawn last, so it covers the renderer rather than hiding behind it.
        if (!target.hasFrame) idle()
    }
}

private const val TAG = "chatty.avatar"
