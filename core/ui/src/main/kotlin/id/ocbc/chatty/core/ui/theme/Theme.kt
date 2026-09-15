package id.ocbc.chatty.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Light is the design canvas's own palette, taken as-is rather than reinterpreted through Material's
 * usual tonal roles: `primaryContainer`/`onPrimaryContainer` map straight to the full
 * [Palette.Red]/[Palette.White] pair instead of a softer tint of them. That mirrors why [DarkColors]
 * keeps the full red too — it is the bank's brand identity wherever it lands, not a surface colour to
 * be diluted by a role's default styling.
 */
private val LightColors = lightColorScheme(
    primary = Palette.Red,
    onPrimary = Palette.White,
    primaryContainer = Palette.Red,
    onPrimaryContainer = Palette.White,
    background = Palette.Canvas,
    onBackground = Palette.Ink,
    surface = Palette.White,
    onSurface = Palette.Ink,
    surfaceContainer = Palette.SurfaceMuted,
    surfaceContainerLow = Palette.SurfaceSubtle,
    surfaceContainerHigh = Palette.White,
    onSurfaceVariant = Palette.InkMuted,
    outline = Palette.OutlineControl,
    outlineVariant = Palette.OutlineBubble,
)

/**
 * Dark is built from the same slate ramp the design's voice mode uses, so the two never look like
 * different products. The red stays the red: it is brand identity, not a surface tint, and dimming
 * it for dark mode would make the customer's own bubbles look disabled.
 */
private val DarkColors = darkColorScheme(
    primary = Palette.Red,
    onPrimary = Palette.White,
    primaryContainer = Palette.RedDeep,
    onPrimaryContainer = Palette.White,
    background = Palette.Slate900,
    onBackground = Palette.OnSlate,
    surface = Palette.Slate800,
    onSurface = Palette.OnSlate,
    surfaceContainer = Palette.Slate700,
    surfaceContainerLow = Palette.Slate900,
    surfaceContainerHigh = Palette.Slate600,
    onSurfaceVariant = Palette.InkFaint,
    outline = Palette.Slate600,
    outlineVariant = Palette.Slate700,
)

/**
 * The app's theme: the OCBC palette from the design canvas, Public Sans, and no dynamic colour.
 *
 * ```
 * setContent { ChattyTheme { AppRoot() } }
 * ```
 */
@Composable
fun ChattyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors

    // The status-bar icons flip with the theme; the window itself is drawn edge to edge by the
    // Activity, so nothing here paints a bar colour.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        shapes = ChattyShapes,
        typography = ChattyTypography,
        content = content,
    )
}
