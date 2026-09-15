package id.ocbc.chatty.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The spacing scale. Every gap in the app is one of these, so "a bit more room here" is a choice
 * between named steps rather than a new magic number.
 */
object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** The screen's side gutter. One value, so nothing on any screen lines up by accident. */
    val gutter = 20.dp
}

/**
 * Radii, taken from the design canvas: 14 for cards and shortcut chips, 18 for chat bubbles, and a
 * fully-round 22 for the composer pill and every circular control.
 */
internal val ChattyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(22.dp),
)

/**
 * A chat bubble's corners, with the one square-ish corner that points at its speaker — the design
 * uses `18 18 18 4` for the assistant and its mirror for the customer.
 */
val AssistantBubbleShape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
val CustomerBubbleShape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
