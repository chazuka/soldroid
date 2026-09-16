package id.ocbc.chatty.customers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.ocbc.chatty.LanguagePill
import id.ocbc.chatty.R
import id.ocbc.chatty.agents.AgentsUiState
import id.ocbc.chatty.agents.AgentsViewModel
import id.ocbc.chatty.core.ai.Agent
import id.ocbc.chatty.core.ai.Brain
import id.ocbc.chatty.core.ai.Language
import id.ocbc.chatty.core.ui.theme.ChattyTheme
import id.ocbc.chatty.core.ui.theme.accentContainer
import id.ocbc.chatty.core.ui.theme.accentOnSurface
import id.ocbc.chatty.core.ui.theme.onSurfaceSecondary
import id.ocbc.chatty.core.ui.theme.Motion
import id.ocbc.chatty.core.ui.theme.Spacing
import id.ocbc.chatty.core.ui.theme.rememberReducedMotion
import id.ocbc.chatty.sharedAgentPortrait
import kotlinx.coroutines.delay

/**
 * The first screen: pick who you are, and the advisor comes with you.
 *
 * This is the design canvas's `05C · Option 1 — stacked list, clean separation`. Each row is one
 * [Customer]: a portrait panel, a hairline, the person's story, and the three words that
 * characterise how they handle money.
 *
 * # Why the list is local and the tap is not
 *
 * The three customers are the demo — bundled portraits and translated copy, neither of which the API
 * has. So the rows are drawn from the first frame, with no skeleton and no wait. What *is* remote is
 * the advisor: [Customer.personaId] is an agent id, and the agent list has to be in before a tap can
 * resolve to one. Rows stay untappable until it is, with a thin progress line saying why, rather
 * than the whole screen being replaced by a spinner for a list that is already there.
 */
@Composable
fun CustomerPickerRoute(
    brain: Brain,
    onBrainChange: (Brain) -> Unit,
    language: Language,
    onToggleLanguage: () -> Unit,
    onSelect: (Agent) -> Unit,
    viewModel: AgentsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CustomerPickerScreen(
        state = state,
        brain = brain,
        onBrainChange = onBrainChange,
        language = language,
        onToggleLanguage = onToggleLanguage,
        onSelect = onSelect,
        onRetry = viewModel::load,
    )
}

@Composable
private fun CustomerPickerScreen(
    state: AgentsUiState,
    brain: Brain,
    onBrainChange: (Brain) -> Unit,
    language: Language,
    onToggleLanguage: () -> Unit,
    onSelect: (Agent) -> Unit,
    onRetry: () -> Unit,
) {
    // The heading scrolls with the cards rather than sitting above them.
    //
    // On a tall handset it makes no difference — nothing scrolls. On a 640dp one it is the whole
    // difference: a badge, three lines of heading, two of subheading and the model row take most of
    // the fold, and pinned above a list they left exactly one card visible on the screen whose job
    // is to show three. As the list's first item the same header simply moves out of the way.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        // The gutter belongs to the list, not to a wrapper, so a card can still be measured against
        // the full width when it needs to be. The bottom tier is padding rather than a trailing
        // spacer item, so it is overscroll the list knows about and not a fourth row to lay out.
        contentPadding = PaddingValues(
            start = Spacing.gutter,
            end = Spacing.gutter,
            top = Spacing.lg,
            bottom = Spacing.lg,
        ),
        // A ceiling on the measure, for the screen turned on its side and for anything wider.
        // Without it a card stretches to the full width, which takes the photo panel with it — it is
        // a share of the card — and runs the description out to about a hundred characters a line.
        // Centred, so the list stays a column rather than drifting to one edge.
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = "header") {
            Header(
                modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH),
                state = state,
                brain = brain,
                onBrainChange = onBrainChange,
                language = language,
                onToggleLanguage = onToggleLanguage,
                onRetry = onRetry,
            )
        }
        itemsIndexed(Customers.List, key = { _, customer -> customer.id }) { index, customer ->
            // Null until the agent list is in, or for good if a deployment has dropped this
            // persona. Either way the row is visible and inert rather than absent.
            val agent = state.agents.firstOrNull { it.id == customer.personaId }
            RevealOnce(index = index) {
                CustomerCard(
                    customer = customer,
                    enabled = agent != null,
                    onClick = { agent?.let(onSelect) },
                    modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH),
                )
            }
        }
    }
}

/**
 * The badge, the question, and — when [MODEL_CHOOSER_VISIBLE] — the model row.
 *
 * # Why the spacing is tiered and not uniform
 *
 * This is what made the screen feel cramped. Every gap used to be the same one step, so the header's
 * own parts — a badge, a two-line heading, a two-line subheading — sat at exactly the distance that
 * was supposed to separate the header from the list. Nothing was grouped, so everything read as one
 * dense block with dead space underneath it.
 *
 * Now the rhythm has tiers: 16 between the badge and the heading, 12 between heading and
 * subheading, and 24 before the cards — plus 24 more above the model row on a build that shows it.
 * Tight inside a group, generous between groups — the badge, title and subtitle belong to each
 * other, and the list is somewhere else.
 */
@Composable
private fun Header(
    modifier: Modifier = Modifier,
    state: AgentsUiState,
    brain: Brain,
    onBrainChange: (Brain) -> Unit,
    language: Language,
    onToggleLanguage: () -> Unit,
    onRetry: () -> Unit,
) {
    Column(modifier) {
        // The badge says what the screen is; the switch says what language it is saying it in. They
        // share the row because they are the same register — a label and a control, both small, both
        // about the screen rather than about a customer.
        Row(verticalAlignment = Alignment.CenterVertically) {
            CoachBadge()
            Spacer(Modifier.weight(1f))
            LanguagePill(language = language, onToggle = onToggleLanguage)
        }
        Spacer(Modifier.height(Spacing.md))
        Text(
            text = stringResource(R.string.customers_title),
            // One step down from the canvas's headlineMedium. Measured on a 384x832dp handset the
            // screen overflowed by about 180dp with the model row shown, and a heading read once is
            // the cheapest place to find some of it — this is still the largest thing on the screen.
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.customers_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceSecondary,
        )

        BrainPicker(available = state.brains, selected = brain, onSelect = onBrainChange)

        // The one place the network shows on this screen. A line rather than a spinner, because the
        // list underneath is already complete and a spinner would claim otherwise.
        AnimatedVisibility(visible = state.loading) {
            Column {
                Spacer(Modifier.height(Spacing.md))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        if (state.failure != null) {
            Spacer(Modifier.height(Spacing.md))
            AgentsUnavailable(reason = state.failure, onRetry = onRetry)
        }

        // The list's own arrangement already separates the header from the cards. The extra tier
        // this used to add was bought back to keep the third card on screen.
    }
}

/**
 * The design's "Financial Coach" eyebrow: a star in a tinted pill.
 *
 * It names what the whole screen is before the heading asks anything, which is what stops the first
 * question — "pick a persona" — reading as an account setting.
 */
@Composable
private fun CoachBadge() {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.accentContainer,
        border = BorderStroke(HAIRLINE, MaterialTheme.colorScheme.accentOnSurface.copy(alpha = ACCENT_EDGE)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = CHIP_PAD_V + 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
        ) {
            Icon(
                imageVector = Icons.Filled.Star,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.accentOnSurface,
                modifier = Modifier.size(BADGE_ICON),
            )
            Text(
                text = stringResource(R.string.customers_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.accentOnSurface,
            )
        }
    }
}

/**
 * Whether this screen offers the model chooser at all.
 *
 * On. The three anonymous buttons are an evaluation control: they let whoever is comparing answers
 * switch stacks without a brand on the button telling them what to think, and a beta is exactly the
 * audience that question is for. Turn it off for a build shown to customers, who have neither the
 * question nor a way to answer it — there the row is noise between the heading and the thing the
 * screen is actually for, and every conversation simply runs on `Brain.Default`.
 *
 * Only the control is conditional. Every brain the build has a key for is constructed and reachable
 * either way, the choice still travels from the app root into the conversation, and the turn trace
 * still records which stack answered — so this flag moves nothing but the row.
 */
private const val MODEL_CHOOSER_VISIBLE = true

/**
 * Which model answers, above the customers rather than inside them.
 *
 * # Why it sits here and not on the cards
 *
 * Customer and model are two different decisions, and a card that carried both would be nine cards
 * and a grid. The customer is who the conversation is about; the model is a property of the session,
 * so it lives above the list, is chosen once, and stays chosen when someone comes back for a second
 * conversation.
 *
 * Hidden entirely when only one model is available, because a chooser with one option is furniture —
 * which is also what keeps this off the screen in a single-key build, where the design has no such
 * control.
 *
 * It also owns the gap above itself. The row is optional in two different ways, so the 24dp that
 * separates it from the subheading has to come and go with it; left behind in the caller it would
 * strand an empty band between the question and the cards on every build that hides the row.
 */
@Composable
private fun BrainPicker(available: List<Brain>, selected: Brain, onSelect: (Brain) -> Unit) {
    if (!MODEL_CHOOSER_VISIBLE || available.size < 2) return

    Spacer(Modifier.height(Spacing.xl))
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
 * Why the rows cannot be tapped, and the way out of it.
 *
 * Inline rather than a full-screen error state: the customers are still on the screen and still
 * worth reading, so replacing them with an apology would take away the only thing that still works.
 * The API's own message is kept because it is the useful one — "A valid API key is required" tells
 * the operator exactly what to fix.
 */
@Composable
private fun AgentsUnavailable(reason: String, onRetry: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(
                start = Spacing.lg,
                end = Spacing.sm,
                top = Spacing.sm,
                bottom = Spacing.sm,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry) { Text(stringResource(R.string.agents_retry)) }
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

    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(Motion.STANDARD_MS)) +
            slideInVertically(tween(Motion.STANDARD_MS)) { it / 6 },
    ) {
        content()
    }
}

/**
 * One customer, as the design's stacked row.
 *
 * The portrait is a full-bleed panel down the left rather than an inset thumbnail, separated by a
 * hairline — that is the whole point of "clean separation": the photo is a column of the card, not a
 * decoration inside it, so three rows read as three people rather than as three paragraphs.
 *
 * # Why the photo is drawn over the row rather than in it
 *
 * It has to be exactly as tall as the text beside it, and the obvious way to say that —
 * `Row(Modifier.height(IntrinsicSize.Min))` with the photo filling it — measures every child's
 * intrinsic height, including the [FlowRow] of chips. A `FlowRow` reports the height of a *single*
 * row, because it cannot know how the chips will wrap until it has a width. So the row came out one
 * chip-row too short and the third trait was silently clipped: "Friendly · Supportive" for Alya,
 * with "Easy to talk to" cut off the bottom.
 *
 * So nothing here asks for an intrinsic measurement. The text column alone decides the card's
 * height, and the portrait is laid over the result with `matchParentSize`, which reads that height
 * instead of contributing to it. A [Spacer] the width of the photo and its hairline keeps the text
 * out from under it.
 *
 * Keeping the bitmap out of the measurement matters twice over: these live in `drawable-nodpi`,
 * where a 238px portrait is 238dp on a 1x handset and 79dp on a 3x one, so a row sized to the image
 * would be a different row on every device.
 *
 * Pressing dips the whole card very slightly rather than tinting it. At this size a ripple is mostly
 * hidden under the thumb, where a scale is felt across the whole row — and it costs nothing, because
 * scale animates on the compositor and never re-lays-out the list.
 */
@Composable
private fun CustomerCard(
    customer: Customer,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val calm = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !calm) PRESSED_SCALE else 1f,
        animationSpec = tween(Motion.QUICK_MS),
        label = "press",
    )

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(MaterialTheme.shapes.large)
            .selectable(
                selected = false,
                enabled = enabled,
                interactionSource = interactions,
                indication = ripple(),
                role = Role.Button,
                onClick = onClick,
            ),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 1.dp,
    ) {
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.fillMaxWidth(PHOTO_FRACTION))
                Spacer(Modifier.width(HAIRLINE))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        // A floor under the row. It is what turns the dead space below the third
                        // card into room inside all three — see [CARD_MIN_HEIGHT].
                        .heightIn(min = CARD_MIN_HEIGHT)
                        .padding(horizontal = Spacing.lg, vertical = Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(CARD_GAP),
                ) {
                    Text(
                        text = customer.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(customer.description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceSecondary,
                        // Three lines covers every description this build ships at the default text
                        // size; the fourth line only ever carried the tail of one sentence. Clamped
                        // rather than rewritten so a longer persona added later degrades quietly
                        // instead of pushing the card — and the whole of it is one tap away.
                        maxLines = DESCRIPTION_MAX_LINES,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Traits(customer.traits)
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = Spacing.lg),
                )
            }

            // Laid over the row it is the same height as. See the doc comment above for why this is
            // not simply the row's first child.
            Row(Modifier.matchParentSize()) {
                Image(
                    painter = painterResource(customer.photo),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth(PHOTO_FRACTION)
                        .fillMaxHeight()
                        .sharedAgentPortrait(customer.personaId),
                )
                VerticalDivider(
                    thickness = HAIRLINE,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

/**
 * The three trait chips, wrapping when the words are long.
 *
 * The first is in the accent colour, as the design draws it: it is the trait that tells this
 * customer apart from the other two, so leading with it means the rows can be scanned instead of
 * read. The rest are quiet, because three equally loud chips are a colour bar, not a hierarchy.
 *
 * They wrap because the words are translated — "Easy to talk to" is "Mudah diajak bicara", and a
 * row that only fits one of those is a row that will clip in the other language.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Traits(traits: List<Int>) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
        verticalArrangement = Arrangement.spacedBy(CARD_GAP),
    ) {
        traits.forEachIndexed { index, trait ->
            TraitChip(text = stringResource(trait), accent = index == 0)
        }
    }
}

@Composable
private fun TraitChip(text: String, accent: Boolean) {
    Surface(
        shape = CircleShape,
        color = if (accent) {
            MaterialTheme.colorScheme.accentContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        border = if (accent) {
            BorderStroke(HAIRLINE, MaterialTheme.colorScheme.accentOnSurface.copy(alpha = ACCENT_EDGE))
        } else {
            null
        },
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (accent) {
                MaterialTheme.colorScheme.accentOnSurface
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(horizontal = CHIP_PAD_H, vertical = CHIP_PAD_V),
        )
    }
}

// The card's own measurements, from the design canvas's 05C Option 1.

/**
 * The portrait's share of the card's width.
 *
 * A fraction, not the design's literal 104dp. That number was drawn against a 402dp canvas, where it
 * is 29% of the card and leaves 189dp for the text. Carried over as a constant it stays 104dp on a
 * 366dp handset, where it is 32% and leaves 153dp — a fifth of the text column gone, which is what
 * pushed every description to four lines and wrapped the chips onto a second row. Proportional keeps
 * the design's balance at whatever width the device actually has.
 */
private const val PHOTO_FRACTION = 0.287f

/**
 * The shortest a row may be.
 *
 * Deliberately taller than the content needs. Three cards at their natural height left about 115dp
 * of nothing below the last one while the text inside them was packed — the space was on the screen,
 * just not where it was wanted. This floor moves it inside the cards.
 */
/** Wide enough for the design's card, narrow enough that the sentence in it stays a sentence. */
private val CONTENT_MAX_WIDTH = 560.dp

/** Lines of a customer's description shown on the card. See the clamp for why three. */
private const val DESCRIPTION_MAX_LINES = 3

private val CARD_MIN_HEIGHT = 132.dp

/** The rhythm inside a card: name to sentence, sentence to chips, chip to chip. */
private val CARD_GAP = 8.dp

private val CHIP_PAD_H = 10.dp
private val CHIP_PAD_V = 3.dp

/** The divider between the portrait and the text, and the width the text is inset to clear it. */
private val HAIRLINE = 1.dp

private const val PRESSED_SCALE = 0.985f
private val BADGE_ICON = 13.dp

/**
 * The hairline around an accent pill.
 *
 * The fill is [androidx.compose.material3.ColorScheme.accentContainer], which in dark mode is the
 * card's own surface — so in dark this edge is the only thing drawing the pill, and it carries more
 * weight than it does in light. A quarter strength reads as a tint in light and as an outline in
 * dark, which is what each mode wants.
 */
private const val ACCENT_EDGE = 0.35f

@Preview
@Composable
private fun CustomerPickerPreview() {
    ChattyTheme {
        Surface {
            CustomerPickerScreen(
                state = AgentsUiState(loading = false, agents = emptyList()),
                brain = Brain.Default,
                onBrainChange = {},
                language = Language.INDONESIAN,
                onToggleLanguage = {},
                onSelect = {},
                onRetry = {},
            )
        }
    }
}
