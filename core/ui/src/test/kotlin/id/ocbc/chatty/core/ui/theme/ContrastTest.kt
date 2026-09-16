package id.ocbc.chatty.core.ui.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.assertTrue
import org.junit.Test

/**
 * The palette's readability, as arithmetic.
 *
 * # Why this is a test and not a review note
 *
 * Contrast is the one design property that is not a matter of taste: WCAG AA is a number, and a
 * pairing either clears it or does not. It is also the property that quietly rots — a colour gets
 * nudged, a role gets remapped, and text that used to be legible is now 3.6:1 on somebody's phone
 * in daylight. Nobody notices in review, because 3.6:1 and 4.6:1 look identical on a good monitor.
 *
 * Every pairing the app actually draws is listed here with the ratio it has to clear. Changing a
 * colour in [Palette] or a role in [ChattyTheme] without re-checking it breaks the build, which is
 * the point.
 *
 * The thresholds are WCAG 2.1: 4.5:1 for body text, 3:1 for text at 18pt+ or for the meaningful
 * parts of an icon.
 */
class ContrastTest {

    @Test
    fun `light mode body text clears AA`() {
        // Headings and names: the design's full ink on its own surfaces.
        assertContrast(Palette.Ink, Palette.Canvas, AA_TEXT, "title on the canvas")
        assertContrast(Palette.Ink, Palette.White, AA_TEXT, "name on a card")

        // Prose. These were the failures: mapped to onSurfaceVariant they ran at 3.6:1 and 3.3:1,
        // which is why `onSurfaceSecondary` exists.
        assertContrast(Palette.InkSecondary, Palette.White, AA_TEXT, "card description")
        assertContrast(Palette.InkSecondary, Palette.Canvas, AA_TEXT, "subtitle under the title")

        // A chip's label sits on a container, where the secondary ink is not enough.
        assertContrast(Palette.Ink, Palette.SurfaceMuted, AA_TEXT, "chip label")
    }

    @Test
    fun `dark mode body text clears AA`() {
        assertContrast(Palette.OnSlate, Palette.Slate900, AA_TEXT, "title on the canvas")
        assertContrast(Palette.OnSlate, Palette.Slate800, AA_TEXT, "name on a card")
        assertContrast(Palette.InkFaint, Palette.Slate800, AA_TEXT, "card description")
        assertContrast(Palette.InkFaint, Palette.Slate900, AA_TEXT, "subtitle under the title")
        assertContrast(Palette.OnSlate, Palette.Slate700, AA_TEXT, "chip label")
    }

    /**
     * The pairing that sent this file into existence.
     *
     * Brand red reads 2.8:1 on the dark slates — not a near miss, an unreadable one. See
     * [accentOnSurface] for why the fix is a lighter tone rather than a heavier background.
     */
    @Test
    fun `brand red clears AA wherever it is read rather than filled`() {
        assertContrast(Palette.RedPressed, Palette.RedWash, AA_TEXT, "light accent on its pill")
        assertContrast(Palette.RedLight, Palette.Slate800, AA_TEXT, "dark accent on a card")
        assertContrast(Palette.RedLight, Palette.Slate900, AA_TEXT, "dark accent on the canvas")
    }

    /** Filled red is the other direction, and has always been fine. Asserted so it stays that way. */
    @Test
    fun `white on brand red clears AA`() {
        assertContrast(Palette.White, Palette.Red, AA_TEXT, "label on a primary button")
        assertContrast(Palette.White, Palette.RedDeep, AA_TEXT, "label on a pressed primary button")
    }

    /**
     * Icons get the 3:1 threshold, not 4.5:1, which is why the muted inks are still the right colour
     * for them where they would be too faint for a sentence. Pinned so that stays a decision rather
     * than an accident — the composer's face button and the thread's typing dots both rely on it.
     */
    @Test
    fun `icons clear the large-element threshold`() {
        assertContrast(Palette.InkMuted, Palette.White, AA_LARGE, "chevron on a light card")
        assertContrast(Palette.InkFaint, Palette.Slate800, AA_LARGE, "chevron on a dark card")
        assertContrast(Palette.InkMuted, Palette.SurfaceMuted, AA_LARGE, "icon on a light control")
        assertContrast(Palette.InkFaint, Palette.Slate700, AA_LARGE, "icon on a dark control")
    }

    /**
     * Guards the assumption [accentOnSurface] and friends are built on: that a scheme can be told
     * light from dark by its own surface. A palette edit that moved a slate above the midpoint
     * would silently hand every screen the light accents.
     */
    @Test
    fun `dark surfaces stay on the dark side of the midpoint`() {
        for (slate in listOf(Palette.Slate900, Palette.Slate800, Palette.Slate700, Palette.Slate600)) {
            assertTrue(relativeLuminance(slate) < 0.5, "$slate reads as a light surface")
        }
        for (light in listOf(Palette.White, Palette.Canvas, Palette.SurfaceMuted, Palette.RedWash)) {
            assertTrue(relativeLuminance(light) >= 0.5, "$light reads as a dark surface")
        }
    }

    private fun assertContrast(foreground: Color, background: Color, minimum: Double, what: String) {
        val ratio = contrastRatio(foreground, background)
        assertTrue(
            ratio >= minimum,
            "$what: %.2f:1, needs %.1f:1".format(ratio, minimum),
        )
    }
}

/** WCAG 2.1 contrast, which is a ratio of relative luminances offset by 0.05. */
private fun contrastRatio(a: Color, b: Color): Double {
    val first = relativeLuminance(a)
    val second = relativeLuminance(b)
    return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
}

/**
 * WCAG relative luminance.
 *
 * Deliberately not [androidx.compose.ui.graphics.luminance]: that is Android's own and this test
 * exists to check the app against the *specification*, so it spells the specification out.
 */
private fun relativeLuminance(color: Color): Double {
    fun channel(value: Float): Double {
        val c = value.toDouble()
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(color.red) + 0.7152 * channel(color.green) + 0.0722 * channel(color.blue)
}

/** Body text, and anything smaller than 18pt. */
private const val AA_TEXT = 4.5

/** Text at 18pt or above, and the meaningful parts of an icon. */
private const val AA_LARGE = 3.0
