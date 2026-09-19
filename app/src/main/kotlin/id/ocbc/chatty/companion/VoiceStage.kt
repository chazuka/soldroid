package id.ocbc.chatty.companion

import android.app.Activity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.core.view.WindowCompat
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import id.ocbc.chatty.LanguagePill
import id.ocbc.chatty.R
import id.ocbc.chatty.core.avatar.AvatarRenderTarget
import id.ocbc.chatty.core.avatar.AvatarSurface
import id.ocbc.chatty.core.ui.theme.Spacing
import id.ocbc.chatty.core.ai.TurnPhase
import id.ocbc.chatty.core.ui.theme.StageColors
import id.ocbc.chatty.core.ui.theme.rememberReducedMotion
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role

/**
 * The stage: the design canvas's voice screen, serving both video and voice-only.
 *
 * # What the canvas specifies, and what this changes
 *
 * The canvas centres a red orb on a slate gradient, with the spoken question quoted large beneath
 * it, the answer under that, and three circular controls at the foot. All of that is kept. The one
 * substitution is the centre: where the canvas shows the orb, [CompanionMode.VIDEO] shows the
 * agent's actual face, because a talking head is the thing this app has that the canvas's product
 * did not. [CompanionMode.VOICE] falls back to the orb, so turning the camera off lands somewhere
 * the design already accounted for rather than on an empty circle.
 *
 * The composition is shared on purpose. Video and voice are one conversation with the face shown or
 * hidden — not two destinations — and building them from one layout is what keeps the switch feeling
 * like a toggle instead of a navigation.
 */
@Composable
fun VoiceStage(
    state: CompanionUiState,
    mode: CompanionMode,
    face: AvatarRenderTarget,
    listening: Boolean,
    partial: String,
    micAvailable: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onInterrupt: () -> Unit,
    onRetry: () -> Unit,
    onOpenText: () -> Unit,
    onToggleVideo: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleHandsfree: () -> Unit,
    onToggleLanguage: () -> Unit,
    onMicTap: () -> Unit,
    onToggleTrace: () -> Unit,
    /** Ends the conversation. The redesigned header has no back arrow; Stop is the way out. */
    onStop: () -> Unit,
) {
    // Keyed on Unit: `interruptible` flips on every turn, and a gesture detector that restarts
    // mid-stream can fire a tap nobody made.
    val canInterrupt = rememberUpdatedState(state.interruptible)

    // The stage is dark whatever the rest of the app is, so the system's own clock and icons have to
    // be light while it is on screen. They follow the app's theme otherwise, and in a light theme
    // that puts a dark clock on a black avatar: on a handset held sideways, where the picture runs
    // under the status bar, the time was unreadable against the subject's hair. Put back on the way
    // out so the screens that *are* light keep their dark icons.
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val wasLight = controller?.isAppearanceLightStatusBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (wasLight != null) {
                controller.isAppearanceLightStatusBars = wasLight
                controller.isAppearanceLightNavigationBars = wasLight
            }
        }
    }

    // Canvas 14 and 14B are one screen with the picture switched off. The pieces are identical —
    // header, what the agent is doing, what was said, three controls — and only their ground
    // changes: a flat slate in voice mode, the face itself in avatar mode.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(StageColors.base)
            // Tapping the stage cuts an answer short. Barge-in is something you do *to a speaker*,
            // and it stays a tap rather than a button because the button of that name now ends the
            // conversation.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { if (canInterrupt.value) onInterrupt() })
            },
    ) {
        // A screen on its side has height to spare nowhere. Everything below measures itself
        // against this rather than against an orientation flag, so a tall-but-short window — a
        // freeform one, a foldable half-open — gets the same treatment.
        val compact = LocalConfiguration.current.screenHeightDp < COMPACT_HEIGHT_DP
        val live = listening || state.phase == TurnPhase.SPEAKING
        val words = @Composable { modifier: Modifier ->
            Column(
                modifier = modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(
                    if (mode == CompanionMode.VIDEO) Spacing.md else Spacing.xl,
                ),
            ) {
                StateBadge(phase = state.phase, listening = listening)
                LevelBars(
                    active = live,
                    bars = if (mode == CompanionMode.VIDEO) BARS_COMPACT else BARS_FULL,
                    height = if (mode == CompanionMode.VIDEO) BAR_MAX_COMPACT else BAR_MAX,
                )
                Exchange(
                    state = state,
                    mode = mode,
                    listening = listening,
                    partial = partial,
                    onRetry = onRetry,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
        val header = @Composable {
            StageHeader(
                state = state,
                mode = mode,
                onToggleVideo = onToggleVideo,
                onToggleMute = onToggleMute,
                onToggleLanguage = onToggleLanguage,
                onToggleTrace = onToggleTrace,
            )
        }

        if (mode == CompanionMode.VIDEO) {
            // How far down the screen the overlaid header reaches: the status bar, plus the band the
            // header itself occupies. The picture is pushed past it so the crown is never behind a
            // button. Read from the window rather than assumed, because the status bar is a
            // different height on a device with a cutout than on one without.
            val headroom = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
                Spacing.md + HEADER_BUTTON + Spacing.sm
            val picture = @Composable { modifier: Modifier ->
                BoxWithConstraints(modifier.then(Modifier.clipToBounds())) {
                    // Edge to edge while the frame is taller than it is wide, which is canvas 14B
                    // and every handset held upright: the stream is cropped at the sides and the
                    // face fills the screen.
                    //
                    // Turned sideways that same fill is a bug rather than a trade. The picture area
                    // becomes wide and short, and a 9:16 stream filling it is cropped to a collar —
                    // the face ends up above the frame entirely. Measured on a rotated handset, not
                    // reasoned about. So on a short screen the surface is given the stream's own
                    // shape and centred, which letterboxes it rather than beheading it.
                    val streamShape = face.frameAspect ?: FALLBACK_ASPECT
                    AvatarSurface(
                        target = face,
                        background = StageColors.base,
                        modifier = if (compact) {
                            // Sideways the surface keeps the stream's own shape and takes the full
                            // height of its half — edge to edge top and bottom, and pinned to the
                            // screen's own edge rather than centred in the half, so the subject sits
                            // against the side of the screen and the slack falls between the face
                            // and the controls instead of splitting either side of it.
                            //
                            // # Why the frame is not made to fill the half
                            //
                            // Because nothing this side of the renderer can choose where the crop
                            // falls. Handed a box wider than the stream it centres its own crop, and
                            // measured on a rotated handset that takes the crown off the top: a
                            // taller surface does not move it, and neither does sliding the drawn
                            // result down — the head is simply not inside what was drawn. Matching
                            // the stream's shape is the one framing where crop and fit agree, so the
                            // head arrives whole. The bands either side are the colour the chroma key
                            // already paints behind the subject, so they are not visible as bands.
                            Modifier.align(Alignment.CenterStart).fillMaxHeight().aspectRatio(streamShape)
                        } else run {
                            // Full width at the stream's own shape, in both orientations.
                            // `requiredHeight` because the result is taller than the box and is
                            // meant to be — the parent clips it. Anchored to the top and then
                            // dropped a touch; see [AVATAR_DROP_MAX].
                            //
                            // Sideways the box is half the screen, which is wider than the stream,
                            // so the same rule crops instead of letterboxing: about half the frame's
                            // height survives, and since the chin sits near the frame's top third
                            // what leaves the screen is chest. Letterboxed to the stream's shape
                            // instead, the face came out a 590px sliver on a 2856px screen.
                            val rendered = maxWidth / streamShape
                            // Upright the picture is now the whole screen and the header floats on
                            // it, so the crown has to be pushed below the header rather than merely
                            // given a touch of air. The frame is dropped by the band the header
                            // occupies, but never by more than the slack the box actually has: on a
                            // 19.5:9 screen a 9:16 stream drawn full width leaves roughly a third of
                            // the height spare, which is far more than the header needs, and the
                            // remainder falls at the foot where the controls and their scrim sit.
                            //
                            // A screen with no slack — a squarer window, a wider stream — gets the
                            // old behaviour instead: a few dp of air and no gap invented under the
                            // header.
                            val slack = (maxHeight - rendered).coerceAtLeast(0.dp)
                            if (slack > 0.dp) {
                                // Fill everything below the header instead of merely reaching into
                                // it. Sized by *height* — the band under the header down to the
                                // bottom edge — and then as wide as that height makes it, which is
                                // wider than the screen, so the sides crop rather than the picture
                                // stopping short of the foot.
                                //
                                // # Why the band above stays
                                //
                                // It cannot be filled. The crown sits about 5% down the frame, so
                                // putting it below a 106dp header while the frame still starts at
                                // the screen's top edge would need a frame over 2000dp tall — the
                                // head several times the size of the screen. Every arrangement of
                                // this stream is a choice between a band above the picture and the
                                // crown behind the clock, and the band is the one that keeps a face
                                // whole. What was available was the *bottom*: the picture used to
                                // stop about 40dp short of the screen's foot and leave slate under
                                // it, and that slack is now part of the face.
                                val height = maxHeight - headroom
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .offset(y = headroom)
                                    .requiredSize(width = height * streamShape, height = height)
                            } else {
                                // No slack: a squarer window, or a wider stream. Full width at the
                                // frame's own shape, clipped by the parent, nudged down a touch.
                                val drop = ((rendered - maxHeight) / 2).coerceIn(0.dp, AVATAR_DROP_MAX)
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth()
                                    .requiredHeight(rendered)
                                    .offset(y = drop)
                            }
                        },
                        idle = {
                            state.agent?.let {
                                AgentPoster(agentId = it.id, displayName = it.displayName)
                            }
                        },
                    )
                    // The foot of the picture carries a sentence and needs to be dark enough to
                    // read it. The top only has a seam to soften where the video meets the slate.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                Brush.verticalGradient(
                                    0f to StageColors.base.copy(alpha = SCRIM_SEAM),
                                    SCRIM_CLEAR_FROM to Color.Transparent,
                                    SCRIM_CLEAR_TO to Color.Transparent,
                                    1f to StageColors.base.copy(alpha = SCRIM_BOTTOM),
                                ),
                            ),
                    )
                    // Sideways the picture is a narrow strip — the stream is a portrait one and
                    // the screen's height is all it has to grow into. Four lines of a question set
                    // across 590px of that strip is a column two words wide laid over a face; in
                    // the space beside it the same four lines read as a sentence. So on a short
                    // screen the words belong to the column, not to the picture.
                }
            }
            val controls = @Composable {
                StageControls(
                    state = state,
                    listening = listening,
                    micAvailable = micAvailable,
                    onPress = onPress,
                    onRelease = onRelease,
                    onOpenText = onOpenText,
                    onStop = onStop,
                    onToggleHandsfree = onToggleHandsfree,
                    onMicTap = onMicTap,
                    compact = compact,
                )
            }

            if (compact) {
                // Side by side, because a screen on its side has width to spare and no height at
                // all. Stacked, the picture and the controls were each squeezed into a band; beside
                // each other the avatar gets the whole height and everything else gets a column.
                //
                // Half the screen each. The picture is wider than the stream's own shape at that
                // size, so it crops rather than letterboxes — top-anchored, which spends the foot
                // of the frame and keeps the head; see [AvatarSurface] below.
                //
                // The picture runs to the edges. Sideways there is no header band over it and
                // nothing else in its half, so insetting it only drew slate margins around a face;
                // the status and navigation bars are held off the *column* instead, and the
                // picture's own top scrim keeps the clock legible where it crosses the frame.
                Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    picture(Modifier.fillMaxHeight().weight(1f))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .statusBarsPadding()
                            .navigationBarsPadding()
                            .padding(horizontal = Spacing.xl)
                            .padding(top = Spacing.md, bottom = Spacing.md),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        header()
                        // What is being said takes the middle of the column, and the controls the
                        // foot of it: reading order down the page, and the thumb reaches the
                        // microphone without crossing the face.
                        words(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(vertical = Spacing.md),
                        )
                        controls()
                    }
                }
            } else {
                // Upright the face is the screen, and everything else floats on it — the canvas's
                // own composition, which earlier versions could not afford.
                //
                // # Why this is affordable now and was not before
                //
                // LiveAvatar composes tightly: measured, the stream carries about 5% of headroom
                // over the subject, some 31dp once it fills a handset, and it does not grow. Drawn
                // edge to edge and top-anchored, the crown landed 26dp down the screen — behind the
                // clock, and behind any header laid over it. The band above the picture was the fix.
                //
                // Full-bleed, the geometry changes: a 9:16 stream drawn full width on a 19.5:9
                // screen is about a third shorter than the screen, and that surplus is a budget the
                // banded layout never had. Spending it as a downward push puts the crown below the
                // header by construction rather than by luck, and the remainder falls at the foot,
                // under the controls, where the bottom scrim already darkens the frame for the
                // caption. Nothing is cropped that was visible before; the picture simply reaches
                // the edges it used to stop short of.
                Box(Modifier.fillMaxSize()) {
                    picture(Modifier.fillMaxSize())
                    Column(Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = Spacing.xl)
                                .padding(top = Spacing.md, bottom = Spacing.sm),
                        ) {
                            header()
                        }
                        // The caption takes whatever is left between the header and the controls and
                        // sits at the bottom of it, so a one-line answer rides just above the
                        // microphone instead of floating in the middle of someone's face. Bounded,
                        // not wrapped: the exchange scrolls inside this rather than growing through
                        // the controls when a reply runs long.
                        words(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.xl)
                                .padding(bottom = Spacing.lg)
                                .wrapContentHeight(Alignment.Bottom),
                        )
                        // Over the picture now, not on a bar of their own. The hesitation a
                        // microphone floating over a chest used to cause came from it sitting on a
                        // live, moving image; the bottom scrim is opaque enough by then that the
                        // controls read as sitting on ground, and the face above them is unbroken.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = Spacing.xl)
                                .padding(top = Spacing.md, bottom = STAGE_BOTTOM_PADDING),
                        ) {
                            controls()
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.xl)
                    .padding(top = Spacing.md, bottom = STAGE_BOTTOM_PADDING),
            ) {
                header()
                // Nothing to look at but the words, so they take the middle.
                words(Modifier.weight(1f).fillMaxWidth().wrapContentHeight(Alignment.CenterVertically))
                Box(Modifier.height(Spacing.lg))
                StageControls(
                    state = state,
                    listening = listening,
                    micAvailable = micAvailable,
                    compact = compact,
                    onPress = onPress,
                    onRelease = onRelease,
                    onOpenText = onOpenText,
                    onStop = onStop,
                    onToggleHandsfree = onToggleHandsfree,
                    onMicTap = onMicTap,
                )
            }
        }
    }
}

/**
 * Who you are talking to, and the two settings that belong to the whole app.
 *
 * Canvas 14 and 14B draw the same bar in both modes, so it is one composable: a round button that
 * swaps the face in and out, the agent's name over what mode you are in, and the language switch.
 *
 * # What is no longer here
 *
 * The back arrow. The canvas replaced it with Stop at the foot of the screen, which is the more
 * honest control — leaving this screen closes a billed provider session, and that is a thing to do
 * on purpose rather than by reflex on the arrow every other screen uses for "up". System back still
 * works and still goes through the same close.
 */
@Composable
private fun StageHeader(
    state: CompanionUiState,
    mode: CompanionMode,
    onToggleVideo: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleLanguage: () -> Unit,
    onToggleTrace: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        GlassCircleButton(
            icon = if (mode == CompanionMode.VIDEO) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
            contentDescription = stringResource(
                if (mode == CompanionMode.VIDEO) R.string.companion_video_off else R.string.companion_video_on,
            ),
            onClick = onToggleVideo,
            size = HEADER_BUTTON,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                // The trace is for whoever is tuning this, not for the customer, so it hides behind
                // a gesture nobody finds by accident.
                .pointerInput(Unit) { detectTapGestures(onLongPress = { onToggleTrace() }) },
            verticalArrangement = Arrangement.spacedBy(Spacing.xs / 2),
        ) {
            Text(
                text = state.agent?.displayName.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    if (mode == CompanionMode.VIDEO) {
                        R.string.companion_mode_avatar
                    } else {
                        R.string.companion_mode_voice
                    },
                ),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = SUBTITLE_ALPHA),
                maxLines = 1,
            )
        }
        // Not in the canvas, and kept anyway: the agent speaks out of the same handset the customer
        // is holding, and the canvas gives no other way to silence it. Placed opposite the camera so
        // the bar stays two round buttons around a name.
        GlassCircleButton(
            icon = if (state.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = stringResource(
                if (state.muted) R.string.companion_unmute else R.string.companion_mute,
            ),
            onClick = onToggleMute,
            size = HEADER_BUTTON,
        )
        LanguagePill(
            onToggle = onToggleLanguage,
            enabled = state.acceptingInput,
            content = Color.White,
            onActive = StageColors.base,
        )
    }
}

/**
 * What the agent is doing, in one word.
 *
 * # Why a badge and not a spinner
 *
 * Three of the four states look identical from outside: a face that is not moving could be waiting
 * for you, thinking about what you asked, or about to speak. The canvas puts a lit pill on the
 * screen for exactly that reason, and the word in it is the answer.
 *
 * The colour follows the state as well as the word — but the word is what carries it, so the badge
 * is still readable to anyone who cannot tell the two reds apart.
 */
@Composable
private fun StateBadge(phase: TurnPhase, listening: Boolean) {
    val label = when {
        listening -> R.string.phase_listening
        phase == TurnPhase.THINKING -> R.string.phase_thinking
        phase == TurnPhase.SPEAKING -> R.string.phase_speaking
        else -> R.string.phase_idle
    }
    val tint = when {
        listening -> StageColors.listening
        phase == TurnPhase.THINKING -> StageColors.thinking
        phase == TurnPhase.SPEAKING -> StageColors.speaking
        else -> StageColors.idle
    }

    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(tint.copy(alpha = BADGE_FILL_ALPHA))
            .padding(horizontal = Spacing.md, vertical = Spacing.xs + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs + 2.dp),
    ) {
        Box(Modifier.size(BADGE_DOT).clip(CircleShape).background(Color.White))
        Text(
            text = stringResource(label).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = BADGE_TRACKING,
            color = Color.White,
        )
    }
}

/**
 * Bars that rise and fall while anyone is talking.
 *
 * The canvas draws them at fixed heights; here they animate, because their whole job is to say "this
 * is live". They are decorative — driven by a timer, not by the audio level — and deliberately so:
 * reading the customer's microphone amplitude to move a row of rectangles would mean holding the
 * recorder open for the length of a conversation to animate a garnish.
 *
 * [bars] and [height] differ by mode, as the canvas draws them: a tall row of eleven in voice mode,
 * where it is the only thing moving on the screen, and a small row of seven in avatar mode, where
 * the face is already doing that job and this is only a sign of life under it.
 */
@Composable
private fun LevelBars(active: Boolean, bars: Int, height: Dp) {
    val calm = rememberReducedMotion()
    val moving = active && !calm
    val pulse = rememberInfiniteTransition(label = "levels")
    Row(
        horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(height),
    ) {
        // The resting pattern repeats when more bars are asked for than the canvas drew, so the row
        // keeps its uneven rhythm at any width instead of running out and flattening.
        List(bars) { BAR_HEIGHTS[it % BAR_HEIGHTS.size] * (height / BAR_MAX) }
            .forEachIndexed { index, resting ->
            val scale by pulse.animateFloat(
                initialValue = if (moving) BAR_MIN_SCALE else 1f,
                targetValue = if (moving) BAR_MAX_SCALE else 1f,
                animationSpec = infiniteRepeatable(
                    // Staggered so the row ripples rather than pumping as one block.
                    animation = tween(BAR_PERIOD_MS + index * BAR_STAGGER_MS),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar$index",
            )
            Box(
                Modifier
                    .width(BAR_WIDTH)
                    .height(resting * scale)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (active) 1f else BAR_IDLE_ALPHA)),
            )
        }
    }
}

/**
 * The current exchange: what was asked, quoted large, and what came back.
 *
 * Only the latest pair. This is a conversation held out loud — the history belongs in text mode, and
 * stacking it here would bury the thing the customer is talking to.
 */
@Composable
private fun Exchange(
    state: CompanionUiState,
    mode: CompanionMode,
    listening: Boolean,
    partial: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lastQuestion = state.transcript.lastOrNull { it.fromCustomer() }?.text
    // The caption, not the draft. On the stage the words sit under a face that is still saying them,
    // and the draft is the model's pace — finished about half a minute before the voice is. See
    // [CompanionUiState.caption]. Falls back to the draft only for the moments the caption cannot
    // cover: before the lips start, and when speech failed and there is no voice to be in step with.
    val streaming = state.draft != null
    // Only the caption. Nothing is shown before the lips move — the typing dots hold that moment —
    // because starting with the model's text and then replacing it with a shorter caption would make
    // the answer appear to shrink. The turn always fills the caption in the end, including when the
    // voice failed or never existed, so this cannot leave the stage blank.
    val answer = state.caption ?: state.transcript.lastOrNull { !it.fromCustomer() }?.text
    val live = listening && partial.isNotEmpty()

    /**
     * One line, and it is always whoever spoke last.
     *
     * # Why the exchange is not kept on screen
     *
     * The stage used to hold the question and the answer together, and they stayed there: a
     * finished exchange sat under the face while the customer was already speaking again. Two
     * utterances, one of them stale, on a surface whose whole job is to show what is happening
     * *now*.
     *
     * This is a conversation being had, not a transcript being read — the transcript is one tap
     * away in [CompanionMode.TEXT] and keeps everything. So the caption follows the turn: the
     * customer's words as they are heard, their question while the model is thinking, then the
     * agent's words as they are spoken, each replacing the last.
     */
    val spoken: SpokenLine? = when {
        // What the microphone is hearing, as it hears it. [liveTail] trims the front, which is the
        // end a speaker needs to see.
        live -> SpokenLine(liveTail(partial), byCustomer = true)

        // Between the question landing and the first word of the answer. Showing what was heard is
        // what makes a mishearing obvious immediately rather than after a strange reply, and the
        // typing dots below say the rest is coming.
        state.phase == TurnPhase.THINKING -> lastQuestion?.let { SpokenLine(it, byCustomer = true) }

        // The answer, revealed in step with the voice saying it.
        answer != null -> SpokenLine(answer, byCustomer = false)

        // A turn that produced nothing to say. Their question is the only thing worth holding, and
        // the retry below is the way out.
        else -> lastQuestion?.let { SpokenLine(it, byCustomer = true) }
    }

    // On the stage the words are a caption, not a document: two lines of question and two of
    // answer, always the newest two, the way broadcast subtitles behave. The customer is
    // *listening* — the text is there to confirm what was heard and to carry the room when the
    // audio is muted, and a wall of it buries whatever it is drawn on.
    //
    // This used to apply to [CompanionMode.VIDEO] alone, which left [CompanionMode.VOICE] showing
    // the whole answer written out beneath the same controls: one stage, two treatments, and the
    // full text repeating what the caption above it had already said. The two are one composition
    // differing only in what stands at their centre — a face or the orb — so they read alike now.
    //
    // Reading the whole answer is what [CompanionMode.TEXT] is for, and it is one tap away.

    Column(
        // No scroll container and no fading edges: clamped text cannot overflow, so both were
        // paying for an affordance nothing needed — the fade masks through an offscreen layer every
        // frame, and the scroll measures content that can no longer be taller than its box.
        modifier = modifier.widthIn(max = EXCHANGE_MAX_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        AnimatedVisibility(visible = spoken?.byCustomer == true, enter = fadeIn(), exit = fadeOut()) {
            val questionStyle = if (mode == CompanionMode.VIDEO) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.headlineSmall
            }
            TailText(
                // Quoted, because on a surface that shows one line at a time the quotes are what
                // say whose words these are.
                text = AnnotatedString("“${spoken?.text.orEmpty()}”"),
                maxLines = CAPTION_MAX_LINES,
                style = questionStyle,
                color = Color.White,
                // Dimmed while it is still being heard: unfinished words, not yet a question.
                modifier = Modifier.alpha(if (listening) LIVE_QUESTION_ALPHA else 1f),
            )
        }
        // Three dots while the model composes. There is a measured ~3.8 s before the first token,
        // and without a sign of life that gap reads as a dropped request.
        AnimatedVisibility(
            visible = state.phase == TurnPhase.THINKING && state.draft.isNullOrEmpty(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            TypingDotsOverPhoto()
        }
        AnimatedVisibility(visible = spoken?.byCustomer == false, enter = fadeIn(), exit = fadeOut()) {
            val body = streamingText(spoken?.text.orEmpty(), streaming = streaming)
            val answerStyle = if (mode == CompanionMode.VIDEO) {
                MaterialTheme.typography.bodyMedium
            } else {
                MaterialTheme.typography.bodyLarge
            }
            val answerColor = Color.White.copy(alpha = ANSWER_ALPHA)
            TailText(text = body, maxLines = CAPTION_MAX_LINES, style = answerStyle, color = answerColor)
        }
        // The turn failed and produced nothing. On the stage there is no thread to fall back on, so
        // the way out has to be here or the customer is left looking at a face that said nothing.
        AnimatedVisibility(visible = state.retryable != null, enter = fadeIn(), exit = fadeOut()) {
            Surface(
                onClick = onRetry,
                enabled = state.acceptingInput,
                shape = CircleShape,
                color = Color.White.copy(alpha = RETRY_FILL_ALPHA),
                contentColor = Color.White,
                border = BorderStroke(1.dp, Color.White.copy(alpha = RETRY_STROKE_ALPHA)),
            ) {
                Text(
                    text = stringResource(R.string.turn_retry),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                )
            }
        }
        if (state.showTrace && state.trace != null) {
            Text(
                text = state.trace.summary(),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = TRACE_ALPHA),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Text clamped to its last [maxLines] lines — a caption, not a paragraph.
 *
 * # Why the tail and not the head
 *
 * Over a face the words track speech that is happening *now*. Clamping a growing answer with
 * `maxLines` alone keeps the opening two lines and hides everything after, so the caption freezes on
 * a sentence that finished seconds ago while the agent talks on. Broadcast subtitles solve this by
 * scrolling the window forward, and this is that: the first line is dropped whenever the text
 * outgrows the box, so what is on screen is always the newest thing said.
 *
 * # How it finds the window
 *
 * By letting the layout tell it. Each pass renders from [start]; if the result still overflows, the
 * first line's width is measured by the very engine that will draw it, and [start] advances past it.
 * That converges in as many passes as there are surplus lines, and it is exact in a way arithmetic
 * over character counts is not — it accounts for the font, the width, the system text scale, and
 * where the words happen to break.
 *
 * [start] survives recomposition on purpose. A streaming answer grows by a token at a time, and the
 * offset found for the previous token is still the right place to start looking for this one; it is
 * reset only when the text stops being an extension of what was measured, which is a new turn.
 *
 * ```
 * TailText(
 *     text = streamingText(answer, streaming = true),
 *     maxLines = 2,
 *     style = MaterialTheme.typography.bodyMedium,
 *     color = Color.White,
 * )
 * ```
 */
@Composable
private fun TailText(
    text: AnnotatedString,
    maxLines: Int,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    // Keyed on the opening of the text rather than the whole of it: the opening is stable while an
    // answer streams and changes the moment a different one starts, which is exactly when the window
    // should be thrown away.
    var start by remember(text.text.take(TAIL_KEY_CHARS)) { mutableIntStateOf(0) }
    val from = start.coerceIn(0, text.length)

    Text(
        text = text.subSequence(from, text.length),
        style = style,
        color = color,
        textAlign = TextAlign.Center,
        maxLines = maxLines,
        // No ellipsis. The end of this text is the live edge — the word being spoken — and marking
        // it as truncated would say the opposite of what is true.
        overflow = TextOverflow.Clip,
        onTextLayout = { layout ->
            if (layout.hasVisualOverflow && layout.lineCount >= maxLines) {
                val firstLine = layout.getLineEnd(0, visibleEnd = true)
                // Offsets from the layout are relative to what was handed to it, so they accumulate.
                // The guard is against a zero-width advance looping forever on a line that cannot be
                // broken any further.
                if (firstLine > 0) start = (from + firstLine).coerceAtMost(text.length)
            }
        },
        modifier = modifier,
    )
}

/**
 * The canvas's three circles: text mode, the microphone, and Stop — each under its own word.
 *
 * # Why every control carries a label
 *
 * Three round buttons with icons in them is three guesses. The canvas labels all three, and the
 * middle one needs it most: the microphone carries two gestures, neither of which a circle can show.
 * The caption under it is also the handsfree toggle, so the gesture has a real control behind it —
 * which is what a screen reader needs and what a double tap cannot offer.
 *
 * # What Stop does
 *
 * It ends the conversation and closes the billed provider session. It is *not* barge-in: cutting an
 * answer short is a tap anywhere on the stage, which is the gesture you already make once you have
 * heard enough. Stop sits at arm's length from the microphone for the same reason a call's red
 * button does.
 *
 * [compact] shrinks the microphone for a screen turned on its side, where this bar was taking
 * nearly half the height and leaving the avatar a letterbox strip.
 */
@Composable
private fun StageControls(
    state: CompanionUiState,
    listening: Boolean,
    micAvailable: Boolean,
    compact: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onOpenText: () -> Unit,
    onStop: () -> Unit,
    onToggleHandsfree: () -> Unit,
    onMicTap: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CONTROL_GAP, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.Top,
    ) {
        LabelledControl(label = stringResource(R.string.companion_mode_text)) {
            GlassCircleButton(
                icon = Icons.Filled.Keyboard,
                contentDescription = stringResource(R.string.companion_mode_text),
                onClick = onOpenText,
            )
        }

        // Weighted, so the middle takes whatever is left after the two side controls have their
        // fixed width — and the caption wraps inside that rather than widening the row.
        //
        // Without it the caption set the row's width, and when the row did not fit, Compose took the
        // shortfall out of the last child: measured on a handset held sideways, End came out an oval
        // while the microphone kept its size. A control that changes shape because of the length of
        // a sentence beside it is a layout bug, not a style.
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            MicButton(
                listening = listening,
                enabled = state.acceptingInput && micAvailable,
                onPress = onPress,
                onRelease = onRelease,
                size = if (compact) MIC_SIZE_LANDSCAPE else MIC_SIZE_PROMINENT,
                contentDescription = stringResource(
                    if (state.handsfree) R.string.companion_handsfree_send else R.string.companion_hold_to_talk,
                ),
                onDoubleTap = if (micAvailable) onToggleHandsfree else null,
                onTap = onMicTap,
                // Between questions the microphone is shut but handsfree is still on, and an
                // idle-looking button there would read as "it has stopped listening to me".
                armed = state.handsfree,
            )
            // The caption is also the button — see the note above on why the gesture needs one.
            Text(
                text = stringResource(
                    if (state.handsfree) R.string.companion_mic_hint_handsfree else R.string.companion_mic_hint_hold,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = if (state.handsfree) 1f else MIC_HINT_ALPHA),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(max = MIC_CAPTION_MAX_WIDTH)
                    .clip(CircleShape)
                    .clickable(
                        enabled = micAvailable,
                        onClickLabel = stringResource(
                            if (state.handsfree) {
                                R.string.companion_handsfree_off
                            } else {
                                R.string.companion_handsfree_on
                            },
                        ),
                        role = Role.Button,
                        onClick = onToggleHandsfree,
                    )
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            )
        }

        LabelledControl(label = stringResource(R.string.companion_stop)) {
            GlassCircleButton(
                icon = Icons.Filled.Stop,
                contentDescription = stringResource(R.string.companion_stop_hint),
                onClick = onStop,
            )
        }
    }
}

/** A round control with the canvas's word underneath it, sized so the row stays even. */
@Composable
private fun LabelledControl(label: String, control: @Composable () -> Unit) {
    Column(
        modifier = Modifier.width(SIDE_CONTROL_WIDTH),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        control()
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = CONTROL_LABEL_ALPHA),
            textAlign = TextAlign.Center,
        )
    }
}

/** How much of the last visible line the scroll hint fades over. */
private val EXCHANGE_FADE = 28.dp
private val CONTROL_GAP = 22.dp
private val STAGE_BOTTOM_PADDING = 32.dp

/**
 * The level meter's resting pattern, taken off canvas 14's eleven bars.
 *
 * Uneven on purpose — a row of equal bars reads as a progress indicator. [LevelBars] repeats the
 * pattern when it is asked for more and scales it to whatever height it is given, so the same
 * rhythm serves the tall voice-mode row and the small one under a face.
 */
private val BAR_HEIGHTS =
    listOf(18.dp, 38.dp, 26.dp, 58.dp, 44.dp, 62.dp, 32.dp, 52.dp, 22.dp, 40.dp, 14.dp)
private val BAR_WIDTH = 5.dp
private val BAR_GAP = 5.dp

/** The tall row, canvas 14. [BAR_HEIGHTS] is stated against this, and scales down from it. */
private val BAR_MAX = 64.dp

private const val BAR_MIN_SCALE = 0.45f
private const val BAR_MAX_SCALE = 1f
private const val BAR_PERIOD_MS = 420
private const val BAR_STAGGER_MS = 70
private const val BAR_IDLE_ALPHA = 0.35f

/** Long-form measure: the question and the answer stay readable rather than running the full width. */
/** The one line on the stage, and who said it. */
private data class SpokenLine(val text: String, val byCustomer: Boolean)

private val EXCHANGE_MAX_WIDTH = 340.dp

/**
 * Trims a live transcript to its last stretch.
 *
 * The recogniser hands back the whole utterance every time, and a long question drawn from the top
 * pushes its own newest words off the bottom — the one part a speaker is actually watching for. This
 * keeps the end and marks the cut.
 *
 * ```
 * liveTail("kalau begitu apa saran kamu supaya tabunganku naik dan pengeluaran bisa lebih hemat")
 * // "…saran kamu supaya tabunganku naik dan pengeluaran bisa lebih hemat"
 * ```
 */
private fun liveTail(text: String): String {
    if (text.length <= LIVE_QUESTION_MAX_CHARS) return text
    val tail = text.takeLast(LIVE_QUESTION_MAX_CHARS)
    // Drop the part-word the cut landed in, unless the cut produced no word break at all.
    return "…" + (tail.substringAfter(' ', tail)).trimStart()
}

/** About four lines of the stage's question type, which is what [liveTail] is trimming to fit. */
private const val LIVE_QUESTION_MAX_CHARS = 160

/**
 * Lines of question and of answer kept on the stage, as captions.
 *
 * Two each. It is the most that fits over a face without the text becoming the subject, and it is
 * what a listener needs: confirmation of what was heard, and the sentence currently being spoken.
 * The rest of the turn is a tap away in text mode, whole and scrollable.
 */
private const val CAPTION_MAX_LINES = 2

/**
 * How much of a text's opening identifies it, for [TailText]'s window.
 *
 * Long enough that two different answers are unlikely to share it, short enough to stay unchanged
 * while the rest of the answer streams in behind it.
 */
private const val TAIL_KEY_CHARS = 32

/** The header's round buttons: smaller than the controls', because they sit beside a name. */
private val HEADER_BUTTON = 42.dp

/**
 * The most the picture is pushed down to put air above the head.
 *
 * A ceiling rather than a fixed inset: the drop is half of whatever vertical surplus the frame has
 * over its box, so a stream or a screen that leaves no surplus simply gets no drop instead of a
 * slate gap under the header.
 *
 * Small, because it is no longer doing the work. With the header on its own band the head is below
 * it by construction — the frame starts there and the subject is 5% further down again. At 36dp the
 * drop was buying air that was already bought and spending 36dp of the figure to do it, which is
 * what made the picture read as cropped. This is the last touch of air, not the whole of it.
 */
private val AVATAR_DROP_MAX = 12.dp

/**
 * The shape to give the surface before the stream has said what shape it is.
 *
 * LiveAvatar publishes 720x1280 and has since this screen was written, so this is only the answer
 * for the handful of frames before [AvatarRenderTarget.frameAspect] arrives with the real one.
 */
private const val FALLBACK_ASPECT = 9f / 16f

/** Below this the window is short enough that the controls have to give the picture its room back. */
private const val COMPACT_HEIGHT_DP = 480

/** The microphone on a short screen. Still well past the 48dp touch floor. */
private val MIC_SIZE_LANDSCAPE = 64.dp

/** The canvas's two rows of level bars: eleven tall ones in voice, seven small ones over a face. */
private const val BARS_FULL = 11
private const val BARS_COMPACT = 7
private val BAR_MAX_COMPACT = 20.dp

/** Both side controls take the same width so the microphone stays centred between them. */
private val SIDE_CONTROL_WIDTH = 72.dp

/**
 * How wide the microphone's caption is allowed to run.
 *
 * Wide enough for the Indonesian sentence — "Tahan untuk bicara · ketuk 2× untuk bebas genggam" — to
 * settle on two lines rather than three, and no wider: the caption sits between the two side
 * controls, and past this it starts to crowd them.
 */
private val MIC_CAPTION_MAX_WIDTH = 180.dp
private const val CONTROL_LABEL_ALPHA = 0.7f
private const val SUBTITLE_ALPHA = 0.75f
private const val INACTIVE_LABEL_ALPHA = 0.65f

/** The state pill: solid enough to read over a face, the dot and the word both white. */
private const val BADGE_FILL_ALPHA = 0.92f
private val BADGE_DOT = 7.dp
private val BADGE_TRACKING = 0.12.em

/**
 * The scrim over the avatar, as the canvas grades it.
 *
 * Dark at the very top and heavily dark at the foot, clear through the middle — the two ends are
 * where the chrome sits, and the face is what the middle is for.
 */
/** Only a seam to soften now, not a band to write a name on. */
private const val SCRIM_SEAM = 0.28f
private const val SCRIM_BOTTOM = 0.92f
private const val SCRIM_CLEAR_FROM = 0.22f
private const val SCRIM_CLEAR_TO = 0.55f

private val HAIRLINE = 1.dp
private val TOGGLE_INSET = 2.dp
private const val GLASS_FILL_ALPHA = 0.10f
private const val GLASS_EDGE_ALPHA = 0.22f
private const val ANSWER_ALPHA = 0.78f
private const val LIVE_QUESTION_ALPHA = 0.75f

/** The caption under the controls, quiet enough to read as help rather than as status. */
private const val MIC_HINT_ALPHA = 0.6f
private const val TRACE_ALPHA = 0.5f
private const val RETRY_FILL_ALPHA = 0.12f
private const val RETRY_STROKE_ALPHA = 0.30f
