package id.ocbc.chatty.agents

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.ocbc.chatty.R
import id.ocbc.chatty.core.ai.Agent
import id.ocbc.chatty.core.ai.AvatarProfile
import id.ocbc.chatty.sharedAgentPortrait
import id.ocbc.chatty.core.ui.theme.ChattyTheme
import id.ocbc.chatty.core.ui.theme.Motion
import id.ocbc.chatty.core.ui.theme.Spacing
import id.ocbc.chatty.core.ui.theme.rememberReducedMotion
import kotlinx.coroutines.delay
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import id.ocbc.chatty.core.ai.Brain

/**
 * The first screen: pick who to talk to.
 *
 * Every agent is one card — a monogram, a name, and the one sentence the API gives about what that
 * agent is for. There is deliberately no preview of the face here: opening a LiveAvatar session is
 * billed per minute, and a list that quietly opened three of them to show thumbnails would cost
 * money before the customer had chosen anything.
 */
@Composable
fun AgentListRoute(
    brain: Brain,
    onBrainChange: (Brain) -> Unit,
    onSelect: (Agent) -> Unit,
    viewModel: AgentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    AgentListScreen(
        state = state,
        brain = brain,
        onBrainChange = onBrainChange,
        onSelect = onSelect,
        onRetry = viewModel::load,
    )
}

@Composable
private fun AgentListScreen(
    state: AgentsUiState,
    brain: Brain,
    onBrainChange: (Brain) -> Unit,
    onSelect: (Agent) -> Unit,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = Spacing.gutter),
    ) {
        Spacer(Modifier.height(Spacing.xxl))
        Text(
            text = stringResource(R.string.agents_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.agents_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Spacing.lg))
        BrainPicker(available = state.brains, selected = brain, onSelect = onBrainChange)
        Spacer(Modifier.height(Spacing.lg))

        when {
            // A skeleton, not a spinner. The list arrives over the network, and a shape that already
            // looks like the answer makes the wait feel like loading rather than like nothing.
            state.loading -> AgentSkeletons()

            state.failure != null -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.failure,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(Spacing.lg))
                    Button(onClick = onRetry) { Text(stringResource(R.string.agents_retry)) }
                }
            }

            else -> LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                itemsIndexed(state.agents, key = { _, agent -> agent.id }) { index, agent ->
                    RevealOnce(index = index) {
                        AgentCard(agent = agent, onClick = { onSelect(agent) })
                    }
                }
                item { Spacer(Modifier.height(Spacing.xl)) }
            }
        }
    }
}

/**
 * Which model answers, above the agents rather than inside them.
 *
 * # Why it sits here and not on the cards
 *
 * Agent and model are two different decisions and a card that carried both would be nine cards and a
 * grid. The agent is who you are talking to; the model is a property of the session, so it lives
 * above the list, is chosen once, and stays chosen when the customer comes back for a second
 * conversation.
 *
 * The labels are numbers on purpose — see [Brain]. Hidden entirely when only one model is available,
 * because a chooser with one option is furniture.
 */
@Composable
private fun BrainPicker(available: List<Brain>, selected: Brain, onSelect: (Brain) -> Unit) {
    if (available.size < 2) return

    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        available.forEachIndexed { index, brain ->
            SegmentedButton(
                selected = brain == selected,
                onClick = { onSelect(brain) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = available.size),
            ) {
                Text(text = brain.label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/**
 * Fades and lifts a row into place, one after another.
 *
 * A list that appears all at once reads as a screenshot; the same list cascading reads as something
 * that just arrived. The stagger is per index and short — long enough to notice, too short to wait
 * for — and it runs once, so scrolling never replays it.
 */
@Composable
private fun RevealOnce(index: Int, content: @Composable () -> Unit) {
    val calm = rememberReducedMotion()
    var shown by remember { mutableStateOf(calm) }
    LaunchedEffect(Unit) {
        if (!calm) {
            delay(index.toLong() * Motion.STAGGER_MS)
            shown = true
        }
    }

    androidx.compose.animation.AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(Motion.STANDARD_MS)) +
            slideInVertically(tween(Motion.STANDARD_MS)) { it / 6 },
    ) {
        content()
    }
}

/**
 * One agent, as a card.
 *
 * Pressing it dips the whole card very slightly rather than tinting it. At this size a ripple is
 * mostly hidden under the thumb, where a scale is felt across the whole row — and it costs nothing,
 * because scale animates on the compositor and never re-lays-out the list.
 */
@Composable
private fun AgentCard(agent: Agent, onClick: () -> Unit) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val calm = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !calm) PRESSED_SCALE else 1f,
        animationSpec = tween(Motion.QUICK_MS),
        label = "press",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(MaterialTheme.shapes.medium)
            .selectable(
                selected = false,
                interactionSource = interactions,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
            ),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Monogram(agent.id, agent.displayName)
            Spacer(Modifier.size(Spacing.lg))
            Column(Modifier.weight(1f)) {
                Text(
                    text = agent.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (agent.tagline.isNotEmpty()) {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(
                        text = agent.tagline,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.size(Spacing.sm))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Three cards' worth of grey, shimmering.
 *
 * Sized to the real rows so the list does not jump when the answer lands — the shape is the promise,
 * and a promise that moves when it is kept is worse than no promise. The shimmer stops entirely when
 * the device has asked for less motion; the skeleton still reads as "loading" without it.
 */
@Composable
private fun AgentSkeletons() {
    val calm = rememberReducedMotion()
    val shimmer = rememberInfiniteTransition(label = "shimmer")
    val progress by shimmer.animateFloat(
        initialValue = 0f,
        targetValue = if (calm) 0f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(SHIMMER_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweep",
    )

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        repeat(SKELETON_ROWS) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(SKELETON_HEIGHT),
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.medium,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            ) {
                Row(
                    modifier = Modifier.padding(Spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ShimmerBlock(progress, Modifier.size(MONOGRAM_SIZE).clip(CircleShape))
                    Spacer(Modifier.size(Spacing.lg))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        ShimmerBlock(
                            progress,
                            Modifier
                                .fillMaxWidth(SKELETON_TITLE_WIDTH)
                                .height(SKELETON_LINE)
                                .clip(MaterialTheme.shapes.extraSmall),
                        )
                        ShimmerBlock(
                            progress,
                            Modifier
                                .fillMaxWidth()
                                .height(SKELETON_LINE)
                                .clip(MaterialTheme.shapes.extraSmall),
                        )
                    }
                }
            }
        }
    }
}

/** One grey block with a highlight sweeping across it, positioned by [progress] (0..1). */
@Composable
private fun ShimmerBlock(progress: Float, modifier: Modifier) {
    val base = MaterialTheme.colorScheme.surfaceContainer
    val highlight = MaterialTheme.colorScheme.background
    Box(
        modifier.background(
            Brush.linearGradient(
                colors = listOf(base, highlight, base),
                // Swept well past both edges so the highlight enters and leaves rather than
                // appearing and vanishing at the boundary.
                start = Offset(progress * SHIMMER_TRAVEL - SHIMMER_WIDTH, 0f),
                end = Offset(progress * SHIMMER_TRAVEL, 0f),
            ),
        ),
    )
}

@Composable
private fun Monogram(agentId: String, name: String) {
    Surface(
        modifier = Modifier
            .size(MONOGRAM_SIZE)
            .sharedAgentPortrait(agentId),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = name.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

private val MONOGRAM_SIZE = 56.dp
private val SKELETON_HEIGHT = 96.dp
private val SKELETON_LINE = 12.dp
private const val SKELETON_ROWS = 3
private const val SKELETON_TITLE_WIDTH = 0.42f
private const val PRESSED_SCALE = 0.985f
private const val SHIMMER_MS = 1_100
private const val SHIMMER_TRAVEL = 900f
private const val SHIMMER_WIDTH = 300f

@Preview
@Composable
private fun AgentListPreview() {
    val profile = AvatarProfile(avatarId = "preview", voices = mapOf("id" to "preview"))
    ChattyTheme {
        Surface {
            AgentListScreen(
                state = AgentsUiState(
                    loading = false,
                    agents = listOf(
                        Agent("emma", "Emma", "a warm, cheerful money buddy for a teenager", profile),
                        Agent("daniel", "Daniel", "a professional, data-driven financial coach", profile),
                    ),
                ),
                brain = Brain.Default,
                onBrainChange = {},
                onSelect = {},
                onRetry = {},
            )
        }
    }
}
