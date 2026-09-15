package id.ocbc.chatty.core.ai

import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import org.junit.Test

class AgentProfilesTest {

    private val profiles = Json { ignoreUnknownKeys = true }.decodeFromString<AgentProfiles>(
        """
        {
          "default": {
            "avatar_id": "face-default",
            "voices": { "id": "voice-default-id", "en": "voice-default-en" }
          },
          "overrides": {
            "daniel": { "voices": { "id": "voice-daniel-id" } },
            "sophia": {
              "avatar_id": "face-sophia",
              "voices": { "id": "voice-sophia-id", "en": "voice-sophia-en" }
            }
          }
        }
        """,
    )

    @Test
    fun `an agent with no entry wears the default face and voices`() {
        val emma = profiles.profileFor("emma")

        assertEquals("face-default", emma.avatarId)
        assertEquals("voice-default-id", emma.voiceFor(Language.INDONESIAN))
        assertEquals("voice-default-en", emma.voiceFor(Language.ENGLISH))
    }

    @Test
    fun `an override of one voice keeps the default for the other language`() {
        val daniel = profiles.profileFor("daniel")

        assertEquals("face-default", daniel.avatarId)
        assertEquals("voice-daniel-id", daniel.voiceFor(Language.INDONESIAN))
        // The override named no English voice, so the default's must survive rather than vanish.
        assertEquals("voice-default-en", daniel.voiceFor(Language.ENGLISH))
    }

    @Test
    fun `a full override replaces the face and both voices`() {
        val sophia = profiles.profileFor("sophia")

        assertEquals("face-sophia", sophia.avatarId)
        assertEquals("voice-sophia-id", sophia.voiceFor(Language.INDONESIAN))
        assertEquals("voice-sophia-en", sophia.voiceFor(Language.ENGLISH))
    }

    @Test
    fun `a deployment with one voice still speaks, in the wrong accent rather than not at all`() {
        val oneVoice = AvatarProfile(avatarId = "face", voices = mapOf("id" to "only"))

        assertEquals("only", oneVoice.voiceFor(Language.ENGLISH))
    }

    @Test
    fun `the two languages are distinct in tag, label and config key`() {
        assertEquals(Language.ENGLISH, Language.INDONESIAN.toggled())
        assertEquals(Language.INDONESIAN, Language.ENGLISH.toggled())
        assertEquals(listOf("id-ID", "en-US"), Language.entries.map { it.tag })
        assertEquals(listOf("ID", "EN"), Language.entries.map { it.label })
    }
}
