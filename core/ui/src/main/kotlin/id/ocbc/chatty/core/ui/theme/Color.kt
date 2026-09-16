package id.ocbc.chatty.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The OCBC palette, taken from the *Chat-only banking* design canvas.
 *
 * # Why these exact values
 *
 * They are the design's, not an interpretation of it. The red is the bank's identity on a screen
 * that asks a customer to trust what it says about their money, and the neutrals are a cool slate
 * family rather than a warm one — which is why Material's dynamic colour stays off: a
 * wallpaper-derived teal would take the identity away and a warm grey would fight the slate.
 */
internal object Palette {
    /** OCBC red. Primary actions, the customer's own chat bubbles, the listening indicator. */
    val Red = Color(0xFFE1251B)
    val RedPressed = Color(0xFFC61A11)
    val RedDeep = Color(0xFFA9130B)

    /**
     * The orb/​glow highlight the design uses at the top-left of the red sphere.
     *
     * Also the brand red's dark-mode reading voice — see [accentOnSurface]. [Red] itself only
     * reaches 2.8:1 against the dark slates, which is unreadable; this clears AA on all of them.
     */
    val RedLight = Color(0xFFFF6A5E)

    /**
     * The tinted pill behind accent text in light mode: [Red] at 8% over white, resolved to an
     * opaque colour.
     *
     * Opaque on purpose. As an alpha wash its contrast depended on whatever it happened to be laid
     * over, and the same chip measured 4.2:1 on a card and 3.8:1 on the canvas — both short of AA,
     * neither obviously so. A fixed colour makes the pair with [RedPressed] a number that can be
     * asserted once.
     */
    val RedWash = Color(0xFFFDEEED)

    // Light surfaces: the thread's paper, and the white the cards sit on.
    val Canvas = Color(0xFFF4F6F7)
    val White = Color(0xFFFFFFFF)
    val SurfaceMuted = Color(0xFFF1F4F5)
    val SurfaceSubtle = Color(0xFFFAFBFB)

    // Hairlines, in the three weights the design actually uses.
    val OutlineHeader = Color(0xFFE7ECEE)
    val OutlineBubble = Color(0xFFE2E8EB)
    val OutlineControl = Color(0xFFDCE3E6)

    // Ink and its quieter registers.
    val Ink = Color(0xFF22313A)
    val InkSecondary = Color(0xFF4B5C65)
    val InkMuted = Color(0xFF7A8A92)
    val InkPlaceholder = Color(0xFF8B99A0)
    val InkFaint = Color(0xFF93A1A8)

    // The dark end of the slate ramp. The design's voice mode is a gradient through these, and the
    // app's dark theme is built from the same family so the two never look like different products.
    val Slate900 = Color(0xFF1E2A31)
    val Slate800 = Color(0xFF22313A)
    val Slate700 = Color(0xFF2B3A43)
    val Slate600 = Color(0xFF33434D)
    val Slate500 = Color(0xFF4B5C65)
    val OnSlate = Color(0xFFF1F4F5)

    /** Behind the avatar, so a slow first frame does not flash the page white. */
    val StageBase = Color(0xFF141C21)

    // Turn-state dots. Red is the design's own "Listening"; the rest complete the set.
    val DotListening = Color(0xFFE1251B)
    val DotThinking = Color(0xFFF2B134)
    val DotSpeaking = Color(0xFF4AA3FF)
    val DotIdle = Color(0xFF8B99A0)
}
