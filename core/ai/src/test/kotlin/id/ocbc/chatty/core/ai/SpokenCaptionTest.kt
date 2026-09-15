package id.ocbc.chatty.core.ai

import kotlin.test.assertEquals
import org.junit.Test

class SpokenCaptionTest {

    @Test
    fun `a second of audio is 48000 bytes`() {
        assertEquals(1_000L, SpokenCaption("satu detik", 48_000).durationMs)
        assertEquals(2_500L, SpokenCaption("dua setengah detik", 120_000).durationMs)
    }

    @Test
    fun `a clause with no audio takes no time`() {
        assertEquals(0L, SpokenCaption("", 0).durationMs)
    }
}
