package id.ocbc.chatty.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colours that belong to the avatar stage rather than to a Material role.
 *
 * They sit outside the colour scheme because they are the same in light and dark: the avatar is a
 * photographic video on a near-black ground in both themes, and the state dots have to stay
 * recognisable — red means listening whichever theme the customer is in.
 *
 * The ground is flat, not a ramp. The canvas's voice screen is a single slate — a gradient behind a
 * full-bleed avatar would be invisible anyway, and behind voice mode it competed with the scrim the
 * words sit on.
 */
object StageColors {
    /** Painted behind the avatar, and the colour the chroma key composites onto. */
    val base: Color = Palette.StageBase

    val idle: Color = Palette.DotIdle
    val listening: Color = Palette.DotListening
    val thinking: Color = Palette.DotThinking
    val speaking: Color = Palette.DotSpeaking

}
