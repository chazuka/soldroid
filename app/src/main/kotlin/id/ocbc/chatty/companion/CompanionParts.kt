package id.ocbc.chatty.companion

import android.content.ClipData
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import id.ocbc.chatty.R
import id.ocbc.chatty.core.ai.Language
import id.ocbc.chatty.core.ai.Speaker
import id.ocbc.chatty.core.ai.TranscriptEntry
import id.ocbc.chatty.core.ai.TurnPhase
import id.ocbc.chatty.core.ui.theme.AssistantBubbleShape
import id.ocbc.chatty.core.ui.theme.CustomerBubbleShape
import id.ocbc.chatty.core.ui.theme.Spacing
import id.ocbc.chatty.core.ui.theme.StageColors
import id.ocbc.chatty.core.ui.theme.rememberReducedMotion
import kotlinx.coroutines.launch

/**
 * The pieces the three companion modes share.
 *
 * They live together because the modes must agree on them: a status dot that meant something
 * different over the avatar than it did in the transcript would make the app feel like two apps.
 */

/**
 * What the companion is doing right now, as the design's outlined pill.
 *
 * A dot carries the state at a glance and the word carries it for anyone who cannot use colour. The
 * dot breathes only while something is actually happening — a pulsing "ready" is noise, and noise
 * that never stops is the fastest way to make a screen tiring.
 */
@Composable
fun StatusPill(phase: TurnPhase, listening: Boolean, modifier: Modifier = Modifier) {
    val (dot, label) = when {
        listening -> StageColors.listening to R.string.phase_listening
        phase == TurnPhase.THINKING -> StageColors.thinking to R.string.phase_thinking
        phase == TurnPhase.SPEAKING -> StageColors.speaking to R.string.phase_speaking
        else -> StageColors.idle to R.string.phase_idle
    }

    val calm = rememberReducedMotion()
    val active = (listening || phase != TurnPhase.IDLE) && !calm
    val pulse = rememberInfiniteTransition(label = "status")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (active) DOT_PULSE_SCALE else 1f,
        animationSpec = infiniteRepeatable(tween(DOT_PULSE_MS), RepeatMode.Reverse),
        label = "dot",
    )

    Surface(
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color.White.copy(alpha = GLASS_STROKE_ALPHA)),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(DOT_SIZE).scale(scale).clip(CircleShape).background(dot))
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = stringResource(label),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White.copy(alpha = OVER_PHOTO_MUTED_ALPHA),
            )
        }
    }
}

/**
 * Hold to talk.
 *
 * Press starts the recogniser, release asks it for what it heard — the same contract as a walkie
 * talkie, which is the one piece of voice UI everybody already understands. A tap too brief to be a
 * hold still produces a result: the recogniser reports no match and the screen says so, which is
 * better than a button that silently does nothing.
 *
 * ```
 * MicButton(
 *     size = 84.dp,
 *     listening = listening,
 *     enabled = state.acceptingInput,
 *     onPress = speech::start,
 *     onRelease = speech::stop,
 * )
 * ```
 */
@Composable
fun MicButton(
    listening: Boolean,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = MIC_SIZE_COMPACT,
    // What the button does right now, which is not fixed: hold-to-talk on the stage, "send this
    // question" in handsfree. A screen reader that says "hold to talk" over a button that submits
    // is worse than no label at all.
    contentDescription: String = stringResource(R.string.companion_hold_to_talk),
    // Handsfree lives on this button rather than on a control of its own: the stage has room for
    // exactly three circles, and the one gesture the microphone was not already using is the double
    // tap — press-and-hold is talking, and a single press is the start of that hold.
    //
    // Null leaves the gesture off entirely, which is what the compact microphone in text mode wants.
    onDoubleTap: (() -> Unit)? = null,
    // A lone short tap: not a hold, and not the first half of a double tap. It means the customer
    // pressed the microphone and nothing happened, which is the moment to say what it wants instead.
    onTap: (() -> Unit)? = null,
    // No custom accessibility action here, deliberately. One was added so a screen reader could
    // reach handsfree without performing a double tap, and it fired on its own: merely reading the
    // accessibility tree invoked it, which toggled the mode and opened the microphone with nobody
    // asking. Measured — an accessibility dump took the listen count from 0 to 2. The accessible way
    // in is the caption beneath the controls, which is a real button with a real label.
    // Drawn as if listening even when it is not: in handsfree the microphone is the mode indicator,
    // and it should not look idle between questions the way it does in hold-to-talk.
    armed: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current

    // The gesture handler below outlives these values, so it reads them through snapshots rather
    // than capturing whatever they happened to be when it was installed.
    val currentEnabled = rememberUpdatedState(enabled)
    val currentOnPress = rememberUpdatedState(onPress)
    val currentOnRelease = rememberUpdatedState(onRelease)
    val currentOnDoubleTap = rememberUpdatedState(onDoubleTap)
    val currentOnTap = rememberUpdatedState(onTap)
    val lit = listening || armed

    // A ring that breathes while the microphone is open, so the customer can see they are being
    // heard without having to trust the small status dot alone.
    val calm = rememberReducedMotion()
    val pulse = rememberInfiniteTransition(label = "mic")
    val halo by pulse.animateFloat(
        initialValue = 1f,
        targetValue = if (listening && !calm) MIC_HALO_SCALE else 1f,
        animationSpec = infiniteRepeatable(tween(MIC_HALO_MS), RepeatMode.Reverse),
        label = "halo",
    )

    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        // The breathing ring means "hearing you right now", never "handsfree is on". Armed and
        // listening have to look different or there is no way to tell whether it caught you, and the
        // ring grows past the button — over the answer above it — which is a price worth paying for
        // live feedback and not for a mode that the caption already states.
        // The canvas's resting glow: two soft rings of the brand red, which is what stops a solid
        // red disc reading as a flat sticker on the slate.
        Box(
            Modifier
                .size(size * MIC_GLOW_OUTER_SCALE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = MIC_GLOW_OUTER_ALPHA)),
        )
        Box(
            Modifier
                .size(size * MIC_GLOW_INNER_SCALE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = MIC_GLOW_INNER_ALPHA)),
        )
        // The breathing ring means "hearing you right now", never "handsfree is on". Armed and
        // listening have to look different or there is no way to tell whether it caught you.
        if (listening) {
            Box(
                Modifier
                    .size(size)
                    .scale(halo)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = MIC_HALO_ALPHA)),
            )
        } else if (lit) {
            // Armed but between questions: a steady ring, so handsfree is visible on the button and
            // not only in the caption under it.
            Box(
                Modifier
                    .size(size * MIC_ARMED_RING_SCALE)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = MIC_ARMED_RING_ALPHA)),
            )
        }
        // A Surface, not a Box carrying `Modifier.background`. The Box form drew its icon but
        // silently skipped its fill on device, so the button vanished against the avatar. A Surface
        // takes colour and shape as parameters rather than as a modifier chain, which is both the
        // fix and what every other control on this screen already does.
        Surface(
            shape = CircleShape,
            // Red at rest, as both canvases draw it. It used to be white until it lit up, which made
            // the one control the screen is built around the only one that looked switched off.
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .size(size)
                .alpha(if (enabled) 1f else DISABLED_ALPHA)
                // Keyed on Unit, never on `enabled`. Restarting a `pointerInput` tears down its
                // gesture detector and starts a new one, and the new detector can pick up the
                // pointer stream mid-flight and fire `onPress` with nobody touching the screen —
                // which here meant a microphone permission dialog opening on its own the moment the
                // agent finished loading and the button became enabled. The flag is read through a
                // snapshot inside the gesture instead, so the handler is installed exactly once.
                .pointerInput(Unit) {
                    detectTapGestures(
                        // Fires on the second release of a double tap. The first tap has already run
                        // the press-and-release pair below by then, which is harmless: a press that
                        // short is treated as an accident by the caller and the recording discarded.
                        onDoubleTap = {
                            val toggle = currentOnDoubleTap.value ?: return@detectTapGestures
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            toggle()
                        },
                        // Fires only once the double-tap window has passed with no second tap, so
                        // this and [onDoubleTap] can never both run for one gesture.
                        onTap = { currentOnTap.value?.invoke() },
                        onPress = {
                            if (!currentEnabled.value) return@detectTapGestures
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnPress.value()
                            tryAwaitRelease()
                            currentOnRelease.value()
                        },
                    )
                }
                ,
        ) {
            Box(contentAlignment = Alignment.Center) {
                // An ear, not a microphone, while handsfree is on. The colour already says the
                // button is in a different state; the glyph says *which* state, and it is the one
                // distinction that matters here — a microphone you hold versus an ear that is
                // listening on its own.
                Icon(
                    imageVector = if (armed) Icons.Filled.Hearing else Icons.Filled.Mic,
                    contentDescription = contentDescription,
                )
            }
        }
    }
}


/**
 * One line of the conversation.
 *
 * The design gives each speaker a tail: the assistant's bubble squares off at the bottom-left and
 * the customer's at the bottom-right, so a glance at the corner tells you who is talking even
 * before colour does. The customer speaks in OCBC red, the assistant on white with a hairline —
 * the quieter side of the pair, because it is the side that does most of the talking.
 */
@Composable
fun Bubble(
    text: String,
    fromCustomer: Boolean,
    modifier: Modifier = Modifier,
    streaming: Boolean = false,
) {
    // Long-press copies. An agent's answer is the sort of thing a customer wants to paste into a
    // note or a message to their partner, and on a phone the only gesture anyone tries for that is
    // a long press. The haptic is the receipt — a toast here would cover the text they just took.
    val clipboard = LocalClipboard.current
    val haptics = LocalHapticFeedback.current
    // The clipboard hands the text to the system rather than writing it, so the call suspends and
    // the long press needs a scope to hand it off from.
    val scope = rememberCoroutineScope()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (fromCustomer) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(
                    if (fromCustomer) CUSTOMER_BUBBLE_MAX_WIDTH else ASSISTANT_BUBBLE_MAX_WIDTH,
                )
                .pointerInput(text) {
                    detectTapGestures(
                        onLongPress = {
                            if (text.isNotBlank()) {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(ClipData.newPlainText(CLIP_LABEL, text)),
                                    )
                                }
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                    )
                },
            color = if (fromCustomer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            contentColor = if (fromCustomer) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            shape = if (fromCustomer) CustomerBubbleShape else AssistantBubbleShape,
            border = if (fromCustomer) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Text(
                // While the answer is still arriving, a caret rides the end of the last line so the
                // customer can tell "still writing" from "finished".
                text = streamingText(text, streaming),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * A circular control over the avatar: the design's 56dp glass disc with a hairline.
 *
 * Used for everything on the stage that is not the microphone — keyboard, stop, camera, mute — so
 * they sit as a set and the microphone's solid fill reads as the primary one among them.
 *
 * [size] is the canvas's two: the 54dp discs either side of the microphone, and the smaller 42dp
 * pair in the header, where they sit beside a name rather than under a label.
 */
@Composable
fun GlassCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = GLASS_BUTTON_SIZE,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = GLASS_FILL_ALPHA),
        contentColor = Color.White,
        border = BorderStroke(1.dp, Color.White.copy(alpha = GLASS_STROKE_ALPHA)),
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(size * GLASS_ICON_RATIO),
            )
        }
    }
}

/** Convenience: the speaker enum rendered as the boolean the bubble actually branches on. */
fun TranscriptEntry.fromCustomer(): Boolean = speaker == Speaker.CUSTOMER

/**
 * The agent's own face, as a still, while the live one is still connecting.
 *
 * # Why this exists
 *
 * Opening a provider session and joining the video room costs about 4.7 s, measured — and roughly
 * 2.9 s of that is the vendor's own handshake, which no amount of client work will remove. For those
 * seconds the stage used to show a breathing letter, which is honest but reads as "nothing is
 * happening".
 *
 * A still of the *same* avatar removes the wait rather than decorating it: the face is simply there,
 * and the live video takes over underneath when it arrives. The stills ship at the source's own 9:16
 * on a black ground, and the video is keyed to black to match, so the swap has nothing to give away.
 *
 * Looked up by name — `res/drawable-nodpi/agent_<id>.webp` — rather than through a `when` over agent
 * ids. A persona the chat API adds tomorrow gets a poster by dropping a file in, and falls back to
 * its monogram until someone does.
 */
@Composable
fun AgentPoster(agentId: String, displayName: String, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val packageName = LocalContext.current.packageName
    val posterId = remember(agentId, resources) {
        resources.getIdentifier("agent_$agentId", "drawable", packageName)
    }

    if (posterId == 0) {
        AgentPortrait(displayName, modifier)
        return
    }

    Image(
        painter = painterResource(posterId),
        contentDescription = null,
        // The still and the video share an aspect ratio, so this crops nothing; it only guarantees
        // the still fills the card even if a future poster is cut slightly differently.
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize(),
    )
}

/**
 * What stands on the stage before the first video frame.
 *
 * Opening a session takes a few seconds, and a flat rectangle for that long reads as a hang. The
 * monogram breathes instead — slowly, so it says "getting ready" without asking to be watched.
 */
@Composable
fun AgentPortrait(name: String, modifier: Modifier = Modifier) {
    val calm = rememberReducedMotion()
    val breath = rememberInfiniteTransition(label = "portrait")
    val alpha by breath.animateFloat(
        initialValue = PORTRAIT_ALPHA_LOW,
        targetValue = if (calm) PORTRAIT_ALPHA_LOW else PORTRAIT_ALPHA_HIGH,
        animationSpec = infiniteRepeatable(tween(PORTRAIT_BREATH_MS), RepeatMode.Reverse),
        label = "breath",
    )

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = name.take(1).uppercase(),
            style = MaterialTheme.typography.displayLarge,
            color = Color.White,
            modifier = Modifier.alpha(alpha),
        )
    }
}

/** Opacity for white text and icons drawn over the avatar's face, so they read without going stark. */
const val OVER_PHOTO_MUTED_ALPHA = 0.85f

/**
 * How much of the row a bubble fills, leaving a margin that shows whose turn it is.
 *
 * The design gives the assistant more room than the customer, because the assistant says more and a
 * paragraph wrapped at 78% reads as cramped where a question does not.
 */
const val ASSISTANT_BUBBLE_MAX_WIDTH = 0.84f
const val CUSTOMER_BUBBLE_MAX_WIDTH = 0.78f

private const val DISABLED_ALPHA = 0.4f
private const val GLASS_FILL_ALPHA = 0.12f
private const val GLASS_STROKE_ALPHA = 0.20f
private const val GLASS_BUBBLE_ALPHA = 0.55f
/** The canvas's two resting halos around the microphone, as a share of the button itself. */
private const val MIC_GLOW_INNER_SCALE = 1.32f
private const val MIC_GLOW_OUTER_SCALE = 1.70f
private const val MIC_GLOW_INNER_ALPHA = 0.14f
private const val MIC_GLOW_OUTER_ALPHA = 0.06f

/** Handsfree, between questions: steady where listening breathes. */
private const val MIC_ARMED_RING_SCALE = 1.16f
private const val MIC_ARMED_RING_ALPHA = 0.35f

private val GLASS_BUTTON_SIZE = 54.dp

/** The glyph's share of the disc, so a 42dp header button and a 54dp control look like one family. */
private const val GLASS_ICON_RATIO = 0.42f
private val DOT_SIZE = 7.dp
private const val DOT_PULSE_SCALE = 1.45f
private const val DOT_PULSE_MS = 700
private const val MIC_HALO_SCALE = 1.35f
private const val MIC_HALO_ALPHA = 0.28f
private const val MIC_HALO_MS = 900
private const val PORTRAIT_ALPHA_LOW = 0.18f
private const val PORTRAIT_ALPHA_HIGH = 0.38f
private const val PORTRAIT_BREATH_MS = 2_200

/** Dark enough to read on the white idle button, warm enough not to look like a system icon. */
private val MIC_IDLE_ICON = Color(0xFF1C1A1E)

/** The composer's microphone, sitting in a row of controls. */
val MIC_SIZE_COMPACT: Dp = 48.dp

/** Voice mode's microphone: the primary control on the screen, sized to be pressed without looking. */
val MIC_SIZE_PROMINENT: Dp = 88.dp

/** What the system shows the copied text as when it lists what is on the clipboard. */
private const val CLIP_LABEL = "message"
