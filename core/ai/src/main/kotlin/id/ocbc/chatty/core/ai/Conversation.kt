package id.ocbc.chatty.core.ai

/** Who said a line. */
enum class Speaker { CUSTOMER, AGENT }

/** One line of the conversation, in the order it happened, as shown in the transcript. */
data class TranscriptEntry(val speaker: Speaker, val text: String)

/**
 * Where a turn is. One turn at a time: the composer and the mic are disabled outside [IDLE], which
 * is what keeps a second question from arriving while the avatar is still rendering the first.
 */
enum class TurnPhase {
    /** No turn in flight. The only phase in which the composer and the mic accept input. */
    IDLE,

    /** The chat API has the question and has not answered yet. */
    THINKING,

    /** The answer is known; audio is being synthesized and streamed to the avatar. */
    SPEAKING,
}

/** One turn on the wire to the chat API. Roles are the OpenAI ones the API validates against. */
data class ChatMessage(val role: Role, val content: String) {
    enum class Role(val wire: String) {
        SYSTEM("system"),
        USER("user"),
        ASSISTANT("assistant"),
    }
}

/** The transcript, as the chat API wants to see it. */
fun List<TranscriptEntry>.asChatHistory(): List<ChatMessage> = map {
    ChatMessage(
        role = when (it.speaker) {
            Speaker.CUSTOMER -> ChatMessage.Role.USER
            Speaker.AGENT -> ChatMessage.Role.ASSISTANT
        },
        content = it.text,
    )
}
