package id.ocbc.chatty.core.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Colours that belong to the avatar stage rather than to a Material role.
 *
 * They sit outside the colour scheme because they are the same in light and dark: the avatar is a
 * photographic video on a near-black ground in both themes, and the state dots have to stay
 * recognisable — red means listening whichever theme the customer is in.
 *
 * The gradient is the design's own voice-mode backdrop. Where the canvas puts a red orb, this app
 * puts the agent's face; the ground behind it is the same slate ramp either way, so voice mode reads
 * as the same screen whether or not the video has arrived.
 */
object StageColors {
    /** Painted behind the avatar, and the colour the chroma key composites onto. */
    val base: Color = Palette.StageBase

    /** Top-to-bottom backdrop for voice mode, matching the design's `#1E2A31 → #33434D → #4B5C65`. */
    val voiceGradient: List<Color> = listOf(Palette.Slate900, Palette.Slate600, Palette.Slate500)

    val idle: Color = Palette.DotIdle
    val listening: Color = Palette.DotListening
    val thinking: Color = Palette.DotThinking
    val speaking: Color = Palette.DotSpeaking

    /** The design's red sphere, kept for the moments the face is absent. */
    val orb: List<Color> = listOf(Palette.RedLight, Palette.Red, Palette.RedDeep)
}
