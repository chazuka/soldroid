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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
                            val drop = ((rendered - maxHeight) / 2).coerceIn(0.dp, AVATAR_DROP_MAX)
                            Modifier
                                .align(Alignment.TopCenter)
                                .fillMaxWidth()
                                .requiredHeight(rendered)
                                .offset(y = drop)
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
                    if (!compact) {
                        words(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.xl)
                                .padding(bottom = Spacing.lg),
                        )
                    }
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
                // The header sits on the slate, above the picture — not over it, as the canvas draws
                // it.
                //
                // # Why the canvas is departed from here
                //
                // LiveAvatar composes tightly: measured, the stream carries about 5% of headroom
                // over the subject, which is some 31dp once it fills a handset. That is the entire
                // budget, and it does not grow. Drawn edge to edge the crown landed 26dp down the
                // screen, behind the clock, and with the header laid over the picture it was behind
                // that too. The canvas's asset has room to spare above its subject and can afford
                // the overlay; this stream cannot.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = Spacing.xl)
                        .padding(top = Spacing.md, bottom = Spacing.sm),
                ) {
                    header()
                }
                picture(Modifier.weight(1f).fillMaxWidth())
                // A solid bar, not more overlay: the canvas ends the picture where the controls
                // begin, and a microphone floating over someone's chest is a control you hesitate
                // over.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(StageColors.base)
                        .navigationBarsPadding()
                        .padding(horizontal = Spacing.xl)
                        .padding(top = Spacing.md, bottom = STAGE_BOTTOM_PADDING),
                ) {
                    controls()
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
    // While the microphone is open, what it has heard replaces the last question: the customer is
    // mid-sentence and needs to see it landing, not read the previous one.
    val live = listening && partial.isNotEmpty()
    val question = if (live) liveTail(partial) else lastQuestion

    val scroll = rememberScrollState()
    // Follow the answer down as it is written, so the line on screen is the line being spoken. The
    // agent is talking through this text at the same time, and a reader left at the top would be
    // looking at a sentence that finished several seconds ago. Only while it is still streaming —
    // once the answer is whole the view stays where the customer left it.
    LaunchedEffect(answer, streaming) {
        if (streaming) scroll.animateScrollTo(scroll.maxValue)
    }

    Column(
        modifier = modifier
            .widthIn(max = EXCHANGE_MAX_WIDTH)
            // A line sliced off at the edge of a scroll region reads as a rendering fault. Fading it
            // out says the text continues and that a drag will reach it — the only affordance a
            // scroll region with no scrollbar has.
            //
            // The fade masks the content's alpha rather than painting a scrim over it. A scrim has
            // to match the background it sits on, and the background here is a gradient: any single
            // colour shows up as a lighter band. Masking is background-independent, so it is correct
            // at every point on the gradient and in both themes.
            .fadingEdges(top = scroll.canScrollBackward, bottom = scroll.canScrollForward)
            // The answer can run past the space reserved for it — a long reply, a large system font
            // — and when it does the customer should be able to read the rest rather than have it
            // silently cut. A drag here is a scroll and a tap still reaches the stage behind, so
            // barge-in survives.
            .verticalScroll(scroll),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        AnimatedVisibility(visible = question != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                text = "“${question.orEmpty()}”",
                style = if (mode == CompanionMode.VIDEO) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                color = Color.White,
                textAlign = TextAlign.Center,
                // A settled question is history and two lines of it is plenty; the whole thing is a
                // tap away in text mode. A live partial is never cut here — [liveTail] has already
                // trimmed it from the front, which is the end a speaker needs to see.
                // Four lines of live transcript over the face, as asked: enough to watch a long
                // question land without the words climbing over the picture they are drawn on.
                // A settled question is history and two lines of it is plenty — the whole thing is a
                // tap away in text mode.
                maxLines = when {
                    !live -> QUESTION_MAX_LINES
                    mode == CompanionMode.VIDEO -> LIVE_QUESTION_MAX_LINES_AVATAR
                    else -> LIVE_QUESTION_MAX_LINES
                },
                overflow = TextOverflow.Ellipsis,
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
        AnimatedVisibility(visible = answer != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                text = streamingText(answer.orEmpty(), streaming = streaming),
                style = if (mode == CompanionMode.VIDEO) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.bodyLarge
                },
                color = Color.White.copy(alpha = ANSWER_ALPHA),
                textAlign = TextAlign.Center,
            )
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
 * Fades the content out at whichever edges are marked, without touching the background.
 *
 * Used on the stage's scrolling exchange. Both edges are wanted because the view follows the answer
 * down as it is written: once it has, the question is above the fold and its last line is the one
 * being sliced.
 *
 * ```
 * Column(Modifier.fadingEdges(top = scroll.canScrollBackward, bottom = scroll.canScrollForward)
 *     .verticalScroll(scroll)) { … }
 * ```
 *
 * The offscreen compositing strategy is not optional: [BlendMode.DstIn] multiplies against what is
 * already in the layer, and without its own layer that is the whole window rather than this
 * composable's content.
 */
private fun Modifier.fadingEdges(top: Boolean, bottom: Boolean): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fade = EXCHANGE_FADE.toPx()
        if (top) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black),
                    startY = 0f,
                    endY = fade,
                ),
                size = Size(size.width, fade),
                blendMode = BlendMode.DstIn,
            )
        }
        if (bottom) {
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.Black, Color.Transparent),
                    startY = size.height - fade,
                    endY = size.height,
                ),
                topLeft = Offset(0f, size.height - fade),
                size = Size(size.width, fade),
                blendMode = BlendMode.DstIn,
            )
        }
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

private const val LIVE_QUESTION_MAX_LINES = 3

/** Over a face there is more room below the picture than beside an orb, and the ask was four. */
private const val LIVE_QUESTION_MAX_LINES_AVATAR = 4
private const val QUESTION_MAX_LINES = 2

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
