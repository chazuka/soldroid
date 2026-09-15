package id.ocbc.chatty.companion

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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
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
    onBack: () -> Unit,
) {
    // Keyed on Unit: `interruptible` flips on every turn, and a gesture detector that restarts
    // mid-stream can fire a tap nobody made.
    val canInterrupt = rememberUpdatedState(state.interruptible)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(StageColors.voiceGradient))
            // Tapping the stage cuts an answer short. Barge-in is something you do *to a speaker*; a
            // dedicated stop button would sit there implying the answer is something to escape.
            .pointerInput(Unit) {
                detectTapGestures(onTap = { if (canInterrupt.value) onInterrupt() })
            }
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.xl)
            .padding(top = Spacing.md, bottom = STAGE_BOTTOM_PADDING),
    ) {
        StageHeader(
            state = state,
            listening = listening,
            onBack = onBack,
            onToggleMute = onToggleMute,
            onToggleLanguage = onToggleLanguage,
            onToggleTrace = onToggleTrace,
        )

        // The stage is a fixed budget of height, and the face is the part that gives.
        //
        // It used to be the other way round: the portrait was a fixed 352dp and whatever was left
        // went to the words. On a handset that left about 90dp for a question and a four-sentence
        // answer, and a Column does not clip — so the answer was drawn straight over the controls at
        // the foot of the screen, half a line of it visible under the microphone. Reserving the
        // text's space first and sizing the face from what remains cannot produce that, at any font
        // scale or screen height.
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Landscape is not portrait with less height. Stacking the face above the words needs
            // about 520dp; a handset on its side has roughly 300, and the budget below then hits its
            // floor and overflows — the controls end up drawn across the bottom of the portrait.
            // Turned sideways there is width to spare instead, so the same two things go side by
            // side and each gets the full height.
            // Captured before the Row and Column scopes below shadow the constraints receiver.
            val available = maxHeight
            val sideBySide = maxWidth > available
            val bars = @Composable { LevelBars(active = listening || state.phase == TurnPhase.SPEAKING) }
            val words = @Composable { modifier: Modifier ->
                Exchange(
                    state = state,
                    listening = listening,
                    partial = partial,
                    onRetry = onRetry,
                    modifier = modifier,
                )
            }

            if (sideBySide) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xl, Alignment.CenterHorizontally),
                ) {
                    when (mode) {
                        CompanionMode.VOICE -> Orb()
                        else -> FacePortrait(
                            state = state,
                            face = face,
                            // The whole height is the face's here: nothing is stacked under it.
                            height = available.coerceIn(FACE_MIN_HEIGHT, FACE_MAX_HEIGHT),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        bars()
                        Box(Modifier.height(Spacing.lg))
                        words(Modifier.weight(1f, fill = false))
                    }
                }
            } else {
                val faceHeight =
                    (available - STAGE_TEXT_RESERVE).coerceIn(FACE_MIN_HEIGHT, FACE_MAX_HEIGHT)

                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when (mode) {
                        CompanionMode.VOICE -> Orb()
                        else -> FacePortrait(state = state, face = face, height = faceHeight)
                    }

                    Box(Modifier.height(Spacing.xl))
                    bars()
                    Box(Modifier.height(Spacing.xl))

                    // `fill = false` is the whole point: the exchange takes what is left and not a
                    // pixel more, but shrinks to its content when the answer is short, so a one-line
                    // reply still sits centred under the face rather than floating in a tall box.
                    words(Modifier.weight(1f, fill = false))
                }
            }
        }

        Box(Modifier.height(Spacing.sm))

        StageControls(
            state = state,
            mode = mode,
            listening = listening,
            micAvailable = micAvailable,
            onPress = onPress,
            onRelease = onRelease,
            onOpenText = onOpenText,
            onToggleVideo = onToggleVideo,
            onToggleHandsfree = onToggleHandsfree,
            onMicTap = onMicTap,
        )
    }
}

/** Who you are talking to, what they are doing, and the two controls that belong to the session. */
@Composable
private fun StageHeader(
    state: CompanionUiState,
    listening: Boolean,
    onBack: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleLanguage: () -> Unit,
    onToggleTrace: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.companion_back),
                tint = Color.White,
            )
        }
        Text(
            text = state.agent?.displayName.orEmpty(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier
                .weight(1f)
                // The trace is for whoever is tuning this, not for the customer, so it hides behind
                // a gesture nobody finds by accident.
                .pointerInput(Unit) { detectTapGestures(onLongPress = { onToggleTrace() }) },
        )
        // The language switch belongs on the stage, not only in the thread.
        //
        // It picks the recogniser as well as the voice, and a spoken question in the wrong one does
        // not come back wrong — it comes back as nonsense: pinned to id-ID, "How much money do I
        // have" was transcribed "Oh macam mana". Android 13's automatic switching is asked for in
        // [rememberSpeechInput], but it is a hint the service may decline, so the customer needs a
        // control they can reach from the screen where they are actually talking.
        //
        // It follows the answer too — see `spokenLanguageFor` — so most of the time this reads as a
        // status light rather than a button.
        TextButton(onClick = onToggleLanguage, enabled = state.acceptingInput) {
            Text(
                text = state.language.label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
        }
        IconButton(onClick = onToggleMute) {
            Icon(
                imageVector = if (state.muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                contentDescription = stringResource(
                    if (state.muted) R.string.companion_unmute else R.string.companion_mute,
                ),
                tint = Color.White,
            )
        }
        StatusPill(phase = state.phase, listening = listening)
    }
}

/**
 * The agent's face, in a portrait card.
 *
 * # Why this is not the canvas's circle
 *
 * The canvas centres a circle, and a circle is right for the orb it was drawn around. The avatar
 * video is **720×1280** — 9:16 — and a 9:16 rectangle cannot fit inside a circle whose diameter is
 * its height: the corners fall outside, so the circle slices the top of the head off flat. Measured,
 * not guessed; the first build of this screen did exactly that.
 *
 * So the video keeps the canvas's *place* and its concentric glow, and takes the shape its own
 * aspect ratio demands. The card matches the source 9:16 exactly, which means no crop and no
 * letterbox — the whole frame, at the size a face needs to read as a person.
 */
@Composable
private fun FacePortrait(state: CompanionUiState, face: AvatarRenderTarget, height: Dp) {
    // The source is 720x1280, so the card is sized from its height at the source's own ratio. Doing
    // it the other way — a fixed width and a height that follows — would letterbox the video on a
    // short screen, which is the one thing a portrait of a person must not do.
    val width = height * FACE_ASPECT

    // Sized to the card, not to the glow. `requiredSize` on the two halo layers below lets them
    // ignore this box's constraints and spill past its edges: they are decoration at 3% and 6%
    // white, and they were costing 92dp of real layout — which is where the answer's second line
    // went. A soft edge that overlaps the level bars is the intent; a portrait that pushes the
    // conversation off the screen is not.
    Box(modifier = Modifier.size(width, height), contentAlignment = Alignment.Center) {
        // The canvas's concentric glow, kept so the video and the orb share a silhouette.
        Box(
            Modifier
                .requiredSize(width + GLOW_OUTER, height + GLOW_OUTER)
                .clip(FaceShape)
                .background(Color.White.copy(alpha = GLOW_FAINT_ALPHA)),
        )
        Box(
            Modifier
                .requiredSize(width + GLOW_INNER, height + GLOW_INNER)
                .clip(FaceShape)
                .background(Color.White.copy(alpha = GLOW_ALPHA)),
        )
        AvatarSurface(
            target = face,
            // Black, to match the bundled stills exactly. The card reads as a framed portrait on the
            // slate gradient, and the still-to-live swap has no background change to give it away.
            background = Color.Black,
            modifier = Modifier.matchParentSize().clip(FaceShape),
            idle = {
                state.agent?.let { AgentPoster(agentId = it.id, displayName = it.displayName) }
            },
        )
    }
}

/**
 * The canvas's red sphere, for when the camera is off.
 *
 * Drawn rather than imported: it is two radial gradients and two rings, and a bitmap of it would be
 * one more asset to keep in step with the brand red.
 */
@Composable
private fun Orb() {
    // Same bargain as the portrait: the halo is drawn outside the layout it occupies, so voice-only
    // mode budgets its height from the orb itself rather than from the glow around it.
    Box(modifier = Modifier.size(ORB_SIZE), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .requiredSize(ORB_SIZE + GLOW_OUTER)
                .clip(CircleShape)
                .background(StageColors.orb[1].copy(alpha = ORB_GLOW_FAINT_ALPHA)),
        )
        Box(
            Modifier
                .requiredSize(ORB_SIZE + GLOW_INNER)
                .clip(CircleShape)
                .background(StageColors.orb[1].copy(alpha = ORB_GLOW_ALPHA)),
        )
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(
                    // Off-centre highlight, as the canvas draws it: a sphere lit from the upper left.
                    Brush.radialGradient(
                        colors = StageColors.orb,
                        center = androidx.compose.ui.geometry.Offset(ORB_LIGHT_X, ORB_LIGHT_Y),
                    ),
                ),
        )
    }
}

/**
 * Seven bars that rise and fall while anyone is talking.
 *
 * The canvas draws them at fixed heights; here they animate, because their whole job is to say "this
 * is live". They are decorative — driven by a timer, not by the audio level — and deliberately so:
 * reading the customer's microphone amplitude to move seven rectangles would mean holding the
 * recorder open for the length of a conversation to animate a garnish.
 */
@Composable
private fun LevelBars(active: Boolean) {
    val calm = rememberReducedMotion()
    val moving = active && !calm
    val pulse = rememberInfiniteTransition(label = "levels")
    Row(
        horizontalArrangement = Arrangement.spacedBy(BAR_GAP),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(BAR_MAX),
    ) {
        BAR_HEIGHTS.forEachIndexed { index, resting ->
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
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                textAlign = TextAlign.Center,
                // A settled question is history and two lines of it is plenty; the whole thing is a
                // tap away in text mode. A live partial is never cut here — [liveTail] has already
                // trimmed it from the front, which is the end a speaker needs to see.
                maxLines = if (live) LIVE_QUESTION_MAX_LINES else QUESTION_MAX_LINES,
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
                style = MaterialTheme.typography.bodyLarge,
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

/** Keyboard, microphone, camera — the canvas's three circles, with its own spacing. */
@Composable
private fun StageControls(
    state: CompanionUiState,
    mode: CompanionMode,
    listening: Boolean,
    micAvailable: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
    onOpenText: () -> Unit,
    onToggleVideo: () -> Unit,
    onToggleHandsfree: () -> Unit,
    onMicTap: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CONTROL_GAP, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassCircleButton(
            icon = Icons.Filled.Keyboard,
            contentDescription = stringResource(R.string.companion_mode_text),
            onClick = onOpenText,
        )
        MicButton(
            listening = listening,
            enabled = state.acceptingInput && micAvailable,
            onPress = onPress,
            onRelease = onRelease,
            size = MIC_SIZE_PROMINENT,
            contentDescription = stringResource(
                if (state.handsfree) R.string.companion_handsfree_send else R.string.companion_hold_to_talk,
            ),
            onDoubleTap = if (micAvailable) onToggleHandsfree else null,
            onTap = onMicTap,
            // Between questions the microphone is shut but handsfree is still on, and an idle-looking
            // button there would read as "it has stopped listening to me".
            armed = state.handsfree,
        )
        GlassCircleButton(
            icon = if (mode == CompanionMode.VIDEO) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
            contentDescription = stringResource(
                if (mode == CompanionMode.VIDEO) R.string.companion_video_off else R.string.companion_video_on,
            ),
            onClick = onToggleVideo,
        )
    }

        // The microphone now carries two gestures and neither is visible on a round button. This
        // line is what keeps handsfree findable — a gesture nobody is told about is a gesture nobody
        // uses — and doubles as the mode readout once it is on.
        // The caption is also the button.
        //
        // It already says what the double tap does, so making it tappable costs nothing visually and
        // gives the mode a real control — which accessibility needs and the gesture cannot provide.
        // The custom action that used to serve that purpose lived on the microphone and fired itself
        // whenever the accessibility tree was read; a labelled, focusable target cannot.
        Text(
            text = stringResource(
                if (state.handsfree) R.string.companion_mic_hint_handsfree else R.string.companion_mic_hint_hold,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = if (state.handsfree) 1f else MIC_HINT_ALPHA),
            textAlign = TextAlign.Center,
            modifier = Modifier
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
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        )
    }
}

/**
 * The video card, at the source's own 9:16. Bigger than the canvas's 150dp orb: an orb only has to
 * register, where a face has to be readable as a person.
 *
 * The height is a range rather than a number because the words come first — see the budget in
 * [VoiceStage]. The floor is where a face stops reading as a person and may as well be the orb; the
 * ceiling is the canvas's own size, which no handset should exceed.
 */
private const val FACE_ASPECT = 9f / 16f
private val FACE_MIN_HEIGHT = 208.dp
private val FACE_MAX_HEIGHT = 352.dp
private val FaceShape = RoundedCornerShape(28.dp)

/**
 * Height set aside for the level bars, their two gaps, and the exchange, before the face is sized.
 *
 * Derived, not guessed: two lines of the question at `headlineSmall` (~32dp each), three lines of
 * the answer at `bodyLarge` (~24dp each), the gap between them, the bars, and `Spacing.xl` above and
 * below. Anything longer than that scrolls or ellipsises inside the exchange rather than growing it.
 */
private val STAGE_TEXT_RESERVE = 216.dp

private val ORB_SIZE = 150.dp

/** The two concentric rings the canvas draws around its sphere. */
private val GLOW_INNER = 44.dp
private val GLOW_OUTER = 92.dp
private const val GLOW_ALPHA = 0.06f
private const val GLOW_FAINT_ALPHA = 0.03f
private const val ORB_GLOW_ALPHA = 0.12f
private const val ORB_GLOW_FAINT_ALPHA = 0.05f
private const val ORB_LIGHT_X = 120f
private const val ORB_LIGHT_Y = 100f

private val BAR_HEIGHTS = listOf(10.dp, 20.dp, 14.dp, 24.dp, 8.dp, 18.dp, 12.dp)
private val BAR_WIDTH = 4.dp
private val BAR_GAP = 4.dp
private val BAR_MAX = 26.dp
private const val BAR_MIN_SCALE = 0.45f
private const val BAR_MAX_SCALE = 1f
private const val BAR_PERIOD_MS = 420
private const val BAR_STAGGER_MS = 70
private const val BAR_IDLE_ALPHA = 0.35f

/**
 * The tail of a live transcript, so the words a speaker just said are the ones on screen.
 *
 * A partial grows while someone talks, and a text box that fills up keeps its beginning and drops
 * the end — the opposite of what the speaker is checking for. There is no ellipsis-at-the-start in
 * Compose, so the trim happens here, at a word boundary, with the ellipsis written in.
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

/** Roughly two lines of `headlineSmall` at the exchange's width. */
private const val LIVE_QUESTION_MAX_CHARS = 64
private const val LIVE_QUESTION_MAX_LINES = 3
private const val QUESTION_MAX_LINES = 2

private val EXCHANGE_MAX_WIDTH = 340.dp

/** How much of the last visible line the scroll hint fades over. */
private val EXCHANGE_FADE = 28.dp
private val CONTROL_GAP = 22.dp
private val STAGE_BOTTOM_PADDING = 32.dp
private const val ANSWER_ALPHA = 0.78f
private const val LIVE_QUESTION_ALPHA = 0.75f

/** The caption under the controls, quiet enough to read as help rather than as status. */
private const val MIC_HINT_ALPHA = 0.6f
private const val TRACE_ALPHA = 0.5f
private const val RETRY_FILL_ALPHA = 0.12f
private const val RETRY_STROKE_ALPHA = 0.30f
