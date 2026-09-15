package id.ocbc.chatty.companion

/**
 * Which of the three full-screen surfaces the customer is looking at.
 *
 * # Why three surfaces and not one split screen
 *
 * A face and a transcript compete for the same attention. Giving each half the screen means the face
 * is too small to read as a person and the transcript too short to read as a conversation — a
 * compromise that serves neither. So each mode takes the whole screen, and the provider session
 * underneath survives every switch: changing mode changes what is drawn, never what is connected.
 *
 * [VIDEO] and [VOICE] are the same composition — the design canvas's voice screen — differing only
 * in what stands at its centre. That is deliberate: they are one conversation with the camera on or
 * off, not two different places, and building them from one layout is what keeps them feeling that
 * way.
 */
enum class CompanionMode {
    /** The agent's face in the centre of the stage. The default, and the reason this app exists. */
    VIDEO,

    /** The same stage with the face replaced by the brand orb — voice only, no talking head. */
    VOICE,

    /** The whole conversation, read rather than heard, on the design's chat thread. */
    TEXT,
    ;

    /** True while this mode draws the dark stage, which is everything except the thread. */
    val isStage: Boolean get() = this != TEXT
}
