package id.ocbc.chatty.companion

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import id.ocbc.chatty.R
import id.ocbc.chatty.core.ui.theme.AssistantBubbleShape
import id.ocbc.chatty.core.ui.theme.rememberReducedMotion

/**
 * The three dots that say the agent is composing an answer.
 *
 * # Why this earns its place
 *
 * Between letting go of the microphone and the first word arriving there is a measured ~3.8 s while
 * the model assembles the customer's record. Without a sign of life that gap reads as a dropped
 * request, and the customer asks again — which is the one thing the turn machine refuses, so their
 * second attempt appears to do nothing too.
 *
 * It lives in the thread as a bubble, in the assistant's own shape and position, so the answer
 * arrives *where the waiting was* rather than somewhere new.
 *
 * ```
 * if (state.phase == TurnPhase.THINKING && state.draft.isNullOrEmpty()) {
 *     TypingBubble()
 * }
 * ```
 */
@Composable
fun TypingBubble(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.phase_thinking)
    Row(modifier = modifier, horizontalArrangement = Arrangement.Start) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            shape = AssistantBubbleShape,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant,
            ),
            // One label for the whole group: a screen reader should say "thinking", not "dot dot dot".
            modifier = Modifier.semantics { contentDescription = description },
        ) {
            TypingDots(
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}

/**
 * The same three dots, for the stage, where there is no bubble to sit in.
 */
@Composable
fun TypingDotsOverPhoto(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.phase_thinking)
    TypingDots(
        color = Color.White,
        modifier = modifier.semantics { contentDescription = description },
    )
}

/**
 * Three dots rising in sequence.
 *
 * Staggered by a third of the cycle each, which is what makes it read as a wave rather than a
 * blink. Holds still entirely when the device has asked for less motion — the dots still say
 * "something is happening here", they just stop insisting on it.
 */
@Composable
private fun TypingDots(color: Color, modifier: Modifier = Modifier) {
    val calm = rememberReducedMotion()
    val pulse = rememberInfiniteTransition(label = "typing")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(DOT_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(DOT_COUNT) { index ->
            val alpha by pulse.animateFloat(
                initialValue = DOT_DIM,
                targetValue = DOT_DIM,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = CYCLE_MS
                        DOT_DIM at 0
                        1f at CYCLE_MS / 3
                        DOT_DIM at (CYCLE_MS * 2) / 3
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = androidx.compose.animation.core.StartOffset(
                        index * (CYCLE_MS / DOT_COUNT),
                    ),
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .size(DOT_SIZE)
                    .clip(CircleShape)
                    .alpha(if (calm) DOT_DIM else alpha)
                    .background(color),
            )
        }
    }
}

/**
 * The answer as it streams, with a caret on the end.
 *
 * # Why a caret rather than just growing text
 *
 * Text that grows on its own is ambiguous — it could be finished. A caret says the sentence is still
 * being written, which is the difference between "wait" and "read". It disappears the moment the
 * answer is complete, so a finished answer never looks like it is still coming.
 *
 * The caret is part of the same `AnnotatedString` rather than a sibling composable, so it sits on
 * the real end of the last line and reflows with it instead of floating beside the paragraph.
 */
@Composable
fun streamingText(text: String, streaming: Boolean): AnnotatedString {
    val calm = rememberReducedMotion()
    val blink = rememberInfiniteTransition(label = "caret")
    val alpha by blink.animateFloat(
        initialValue = 1f,
        targetValue = if (streaming && !calm) 0f else 1f,
        animationSpec = infiniteRepeatable(tween(CARET_MS), RepeatMode.Reverse),
        label = "blink",
    )

    val caretColor = MaterialTheme.colorScheme.primary
    return buildAnnotatedString {
        append(text)
        if (streaming) {
            withStyle(SpanStyle(color = caretColor.copy(alpha = alpha))) { append(CARET) }
        }
    }
}

/** A block, not a line: a line is easily read as an "l" at the end of a word. */
private const val CARET = "█"

private const val DOT_COUNT = 3
private val DOT_SIZE = 7.dp
private val DOT_GAP = 5.dp
private const val DOT_DIM = 0.28f
private const val CYCLE_MS = 900
private const val CARET_MS = 520

/** Unused today, but the caret needs a style hook if it is ever themed separately. */
internal val CaretStyle = TextStyle()
