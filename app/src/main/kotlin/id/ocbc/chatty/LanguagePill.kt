package id.ocbc.chatty

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import id.ocbc.chatty.core.ai.Language

/**
 * The language switch: both languages visible, the live one filled.
 *
 * # Why there is only one of these
 *
 * There used to be three. The stage drew the canvas's `ID|EN` segmented pill; the picker and the
 * thread each drew a circle with a single letter in it that swapped on tap. A single letter says
 * what you are *in* but not that the other one exists, and least of all that the thing is a switch —
 * so the same decision looked like a different control on every screen, and on two of them it did
 * not look like a control at all. The canvas's pill is the standard, and this is it.
 *
 * # How it sits on both a dark stage and a light page
 *
 * It takes its colours from the content colour it inherits rather than from a fixed palette: the
 * container and the hairline are that colour at low alpha, and the selected segment is that colour
 * filled, with [onActive] — whatever the pill is sitting on — written on top. On the stage the
 * caller provides white; on a themed surface the defaults do the right thing in light and dark.
 *
 * ```
 * LanguagePill(onToggle = onToggleLanguage)                                     // on a page
 * LanguagePill(                                                                 // on the stage
 *     onToggle = onToggleLanguage,
 *     content = Color.White,
 *     onActive = StageColors.base,
 * )
 * ```
 */
@Composable
fun LanguagePill(
    onToggle: () -> Unit,
    // Defaults to the language the app is written in, which is the thing this switch actually
    // controls. Not the conversation's — that one follows the model's answers, and a switch that
    // flips itself while the screen stays in the other language reads as a bug, because it is one.
    language: Language = LocalAppLanguage.current,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: Color = LocalContentColor.current,
    onActive: Color = MaterialTheme.colorScheme.surface,
) {
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(content.copy(alpha = CONTAINER_ALPHA))
            .border(HAIRLINE, content.copy(alpha = EDGE_ALPHA), CircleShape)
            .clickable(
                enabled = enabled,
                onClickLabel = stringResource(R.string.companion_language),
                onClick = onToggle,
            )
            .padding(INSET),
    ) {
        // Declaration order, so Indonesian is always the left-hand segment. The pair must not
        // reorder itself around the selection — a switch whose halves move is a switch nobody trusts.
        for (option in Language.entries) {
            val on = option == language
            Text(
                text = option.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Bold,
                color = if (on) onActive else content.copy(alpha = INACTIVE_ALPHA),
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (on) content else Color.Transparent)
                    .padding(horizontal = SEGMENT_PAD_H, vertical = SEGMENT_PAD_V),
            )
        }
    }
}

private val HAIRLINE = 1.dp
private val INSET = 2.dp
private val SEGMENT_PAD_H = 10.dp
private val SEGMENT_PAD_V = 4.dp
private const val CONTAINER_ALPHA = 0.10f
private const val EDGE_ALPHA = 0.22f
private const val INACTIVE_ALPHA = 0.65f
