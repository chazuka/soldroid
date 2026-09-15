package id.ocbc.chatty.core.ai

import kotlin.test.assertEquals
import org.junit.Test

class ConversationTest {

    @Test
    fun `the transcript becomes OpenAI roles in order`() {
        val transcript = listOf(
            TranscriptEntry(Speaker.CUSTOMER, "halo"),
            TranscriptEntry(Speaker.AGENT, "hai"),
            TranscriptEntry(Speaker.CUSTOMER, "berapa tabunganku"),
        )

        val history = transcript.asChatHistory()

        assertEquals(
            listOf(ChatMessage.Role.USER, ChatMessage.Role.ASSISTANT, ChatMessage.Role.USER),
            history.map { it.role },
        )
        assertEquals(transcript.map { it.text }, history.map { it.content })
    }
}
