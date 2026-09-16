package id.ocbc.chatty.core.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The three colour roles Material's scheme does not have and this app keeps needing.
 *
 * # Why these exist
 *
 * [LightColors] and [DarkColors] map the design canvas onto Material's roles, and the mapping is
 * honest everywhere the design agrees with Material. These are the three places it does not:
 *
 *  - **Brand red as *text*.** `primary` is the red a button is *filled* with, where white sits on
 *    top of it and the contrast is fine. Used the other way round — red glyphs on a surface — the
 *    same colour reads 2.8:1 on the dark slates and 4.2:1 on a light tint. Both fail AA, and the
 *    dark one is genuinely hard to read. Material's answer is a lighter tone in dark themes;
 *    [accentOnSurface] is that tone, drawn from the palette the design already contains.
 *  - **The pill behind it.** [accentContainer] is the surface that pairing assumes, so the ratio is
 *    a property of two named colours rather than of whatever the chip was laid over that day.
 *  - **Body copy on a card.** The design writes card prose in `#4B5C65` and page subtitles in the
 *    lighter `#7A8A92`, and Material has one `onSurfaceVariant` for both. Mapped to the lighter one,
 *    every description on the picker sat at 3.6:1. [onSurfaceSecondary] is the design's own middle
 *    register, restored.
 *
 * Every pairing below is asserted in `ContrastTest`, so a palette edit that breaks one fails the
 * build rather than shipping.
 *
 * ```
 * Text(text = customer.name, color = MaterialTheme.colorScheme.onSurface)
 * Text(text = story, color = MaterialTheme.colorScheme.onSurfaceSecondary)
 * ```
 *
 * # Why they read the scheme instead of asking the theme
 *
 * `isSystemInDarkTheme()` answers what the *system* is set to, which is not always what is being
 * drawn: [ChattyTheme] takes a `darkTheme` argument, and a `@Preview` renders whichever it is told
 * to. The scheme in hand is the thing actually being used, so its own [ColorScheme.surface] is what
 * decides.
 */
private val ColorScheme.isDark: Boolean
    get() = surface.luminance() < MID_LUMINANCE

/**
 * Brand red, safe to read as text or an icon on [ColorScheme.surface] or
 * [ColorScheme.background].
 *
 * Light `#C61A11` on [Palette.RedWash] is 5.2:1; dark `#FF6A5E` on the slates is 4.8:1 and 5.2:1.
 * Not for filling anything — a shape the customer taps is still `primary`, with `onPrimary` on it.
 */
val ColorScheme.accentOnSurface: Color
    get() = if (isDark) Palette.RedLight else Palette.RedPressed

/**
 * The surface [accentOnSurface] is measured against.
 *
 * Light is the tinted pill the design draws. Dark is the card's own surface — an accent pill in
 * dark mode is an outline and a colour, not a fill, because any wash light enough to see is also
 * light enough to drag the text under 4.5:1.
 */
val ColorScheme.accentContainer: Color
    get() = if (isDark) surface else Palette.RedWash

/**
 * Prose that is not the heading: a card's description, a subtitle under a title.
 *
 * Sits between [ColorScheme.onSurface] and [ColorScheme.onSurfaceVariant]. Use it on
 * [ColorScheme.surface] and [ColorScheme.background] only — on the containers it drops to 4.4:1,
 * which is what [ColorScheme.onSurface] is for.
 */
val ColorScheme.onSurfaceSecondary: Color
    get() = if (isDark) Palette.InkFaint else Palette.InkSecondary

/** Halfway up the luminance range: above it a surface is light, below it dark. */
private const val MID_LUMINANCE = 0.5f
