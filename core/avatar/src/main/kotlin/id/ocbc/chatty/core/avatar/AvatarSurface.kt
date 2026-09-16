package id.ocbc.chatty.core.avatar

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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

    /**
     * The shape of the stream, as the stream reports it — width over height, rotation applied.
     *
     * Null until the first frame's dimensions arrive, which is the caller's cue to draw at whatever
     * ratio it assumes in the meantime. It is deliberately not seeded with a guess: a null that means
     * "not known yet" is honest, where a default would be an assumption wearing a measurement's
     * clothes.
     *
     * # Why the shape has to come from here
     *
     * The frame is the provider's to choose, and it is the number a caller needs to size a card
     * without letterboxing it. Hard-coding it works right up until the provider re-encodes, at which
     * point nothing fails — the picture just quietly sits in the wrong-shaped box with bars nobody
     * asked for. Reading it makes that self-correcting.
     *
     * ```
     * val aspect = face.frameAspect ?: FALLBACK_ASPECT
     * Box(Modifier.size(height * aspect, height)) { AvatarSurface(face, …) }
     * ```
     *
     * Written from the renderer thread, like [hasFrame]. Reset when the track is replaced, because
     * the next one may be shaped differently.
     */
    var frameAspect: Float? by mutableStateOf(null)
        internal set
}

/**
 * Builds an [AvatarRenderTarget] bound to [controller], keyed on nothing but the composition it sits
 * in — put it somewhere that outlives the individual screens, or it defeats its own purpose.
 *
 * [background] is the colour the chroma key composites onto, and should match whatever the face will
 * be drawn against.
 *
 * [crop] picks how the stream meets its frame. The default fits it: the whole picture, with any
 * letterbox invisible because the bars are the colour the shader just painted behind the subject.
 * Pass true only when the frame has deliberately been made a different shape from the stream and the
 * caller wants the picture to fill it — libwebrtc centres that crop, so it takes from the top and
 * the bottom of the frame equally.
 */
@Composable
fun rememberAvatarRenderTarget(
    controller: AvatarController,
    background: Color,
    crop: Boolean = false,
): AvatarRenderTarget {
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
            onFrameSize = { width, height, rotation ->
                // A quarter turn swaps what the decoder reports for what is actually drawn.
                val upright = rotation % HALF_TURN_DEGREES != 0
                val shown = if (upright) height to width else width to height
                val aspect = if (shown.second > 0) shown.first.toFloat() / shown.second else null
                if (aspect != null && aspect != target.frameAspect) {
                    Log.i(TAG, "stream is ${shown.first}x${shown.second} (aspect %.3f)".format(aspect))
                    target.frameAspect = aspect
                }
            },
        )
        onDispose { target.renderer.release() }
    }

    // Its own effect, not part of the init above: [crop] changes when the frame the caller draws
    // changes shape — a rotation does exactly that — and re-running `initRenderer` for a scaling
    // mode would tear down a working renderer to change one enum.
    LaunchedEffect(target, crop) {
        target.renderer.setScalingType(
            if (crop) {
                RendererCommon.ScalingType.SCALE_ASPECT_FILL
            } else {
                RendererCommon.ScalingType.SCALE_ASPECT_FIT
            },
        )
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
            // The next track reports its own shape; until it does, nothing is known about it.
            target.frameAspect = null
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
 * Scaling is aspect-**fit** by default, not fill. Filling crops a portrait frame to the view's shape
 * and takes some of the top of the head with it; fitting shows the whole frame and lets the
 * letterbox disappear, because the bars are the same colour the shader just painted behind the
 * subject. The choice belongs to whoever sized the frame — see `rememberAvatarRenderTarget`'s
 * `crop`.
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

/** A half turn. Rotation past it in either direction swaps the frame's width and height. */
private const val HALF_TURN_DEGREES = 180
