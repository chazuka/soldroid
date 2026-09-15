package id.ocbc.chatty.core.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Whether this device has asked for animation to be kept to a minimum.
 *
 * # Why bother
 *
 * Several surfaces here animate forever: the status dot breathes, the microphone haloes, the level
 * bars ripple. For most people that is the difference between a screen that feels alive and one that
 * feels dead. For someone with a vestibular disorder it is the difference between a usable app and a
 * headache, and Android's accessibility settings are how they say so.
 *
 * Compose has no `prefers-reduced-motion`, so this reads the platform's animator duration scale —
 * the value behind Settings → Accessibility → Remove animations, and behind the developer-options
 * animation scales. Zero means "no animation, please".
 *
 * # How to use it
 *
 * Gate the *looping* animations, not the functional ones. A transition that tells the customer where
 * something went is information; a dot that pulses forever is decoration.
 *
 * ```
 * val calm = rememberReducedMotion()
 * val scale by transition.animateFloat(
 *     targetValue = if (calm || !active) 1f else 1.45f,
 *     …
 * )
 * ```
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}

/**
 * The motion vocabulary, so every surface moves at the same speed.
 *
 * Durations follow the platform guidance the design canvas is built on: micro-interactions land in
 * 150–300 ms, and an exit runs at roughly two-thirds of its entrance so dismissing feels quicker
 * than summoning.
 */
object Motion {
    /** A press, a ripple, a colour change. Short enough to read as instant cause and effect. */
    const val QUICK_MS = 150

    /** The default for anything that moves or resizes. */
    const val STANDARD_MS = 260

    /** Exits, at roughly two-thirds of [STANDARD_MS]. */
    const val EXIT_MS = 170

    /** A screen-scale change: a mode swap, a sheet. */
    const val LARGE_MS = 380

    /** Per-item delay when a list reveals itself. Enough to read as a cascade, not as a queue. */
    const val STAGGER_MS = 40
}
