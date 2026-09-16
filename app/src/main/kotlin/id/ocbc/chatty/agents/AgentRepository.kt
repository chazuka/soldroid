package id.ocbc.chatty.agents

import id.ocbc.chatty.core.ai.Agent
import id.ocbc.chatty.core.ai.AgentProfiles
import id.ocbc.chatty.core.ai.ChatClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The agent list, joined from the two halves that know about it.
 *
 * The chat API knows who the agents are and what they are for; `assets/agents.json` knows what each
 * one should look and sound like. Neither half is authoritative alone, and this is the only place
 * they meet — so a persona the API adds tomorrow appears in the list immediately, wearing the
 * default face and voice until someone gives it its own.
 *
 * Cached for the life of the process: the list is three rows that change when a deployment changes,
 * and re-fetching it every time the customer backs out of a conversation buys nothing.
 */
@Singleton
class AgentRepository @Inject constructor(
    private val chat: ChatClient,
    private val profiles: AgentProfiles,
) {
    private val guard = Mutex()
    private var cached: List<Agent>? = null

    /**
     * The full agent list, joining the chat API's personas with their configured face and voice.
     *
     * The first call fetches from [chat] and caches the result; every call after that returns the
     * cached list. [guard] serialises concurrent callers so a slow first fetch cannot be started twice.
     */
    suspend fun agents(): List<Agent> = guard.withLock {
        cached ?: chat.agents()
            .map { Agent(it.id, it.displayName, it.tagline, profiles.profileFor(it.id)) }
            .also { cached = it }
    }

    /** The agent behind an id, or null if the list no longer has one — a deployment can drop a persona. */
    suspend fun agent(id: String): Agent? = agents().firstOrNull { it.id == id }
}
