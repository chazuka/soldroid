package id.ocbc.chatty

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * The scopes a shared-element transition needs, carried down instead of threaded through.
 *
 * Compose's shared-element API wants two scopes at the call site: the [SharedTransitionScope] that
 * owns the overlay, and the [AnimatedVisibilityScope] of the screen currently animating. Passing
 * both as parameters would put transition plumbing in the signature of every composable between the
 * root and the one element that actually moves — including screens that do not animate at all.
 *
 * Both default to null so a `@Preview`, a test, or any caller outside an [AnimatedVisibilityScope]
 * renders the element normally instead of crashing.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** The [AnimatedVisibilityScope] of the screen currently animating — see [LocalSharedTransitionScope]. */
val LocalAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Marks this element as the agent's portrait, so it flies from the list row to the stage.
 *
 * The monogram is the only thing both screens have in common, which makes it the right handle: the
 * customer taps a letter in a list and that same letter becomes the face's stand-in while the
 * session opens. Without it the two screens are a cut, and a cut makes the second screen feel like a
 * different app rather than a place the first one led to.
 *
 * ```
 * Text(name.take(1), modifier = Modifier.sharedAgentPortrait(agent.id))
 * ```
 *
 * A no-op wherever the scopes are absent.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedAgentPortrait(agentId: String): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val animated = LocalAnimatedVisibilityScope.current ?: return this
    with(shared) {
        return this@sharedAgentPortrait.sharedElement(
            sharedContentState = rememberSharedContentState(key = "agent-portrait-$agentId"),
            animatedVisibilityScope = animated,
        )
    }
}
