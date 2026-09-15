package id.ocbc.chatty.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Request

/**
 * Fetches the customer record a persona is advising on.
 *
 * # Why this is separate from [ChatClient]
 *
 * The demo API keeps each persona's customer server-side and never needs to hand it over — until the
 * answer is coming from somewhere else. A frontier model has no idea who it is talking to, so the
 * record has to travel with the question. This is the only reason the endpoint is called at all, and
 * [Brain.needsBriefing] is the test for whether it is worth calling.
 *
 * ```
 * val record = records.of("daniel")           // raw JSON, straight into the system prompt
 * ```
 */
interface CustomerRecords {
    /**
     * The record for [personaId], as the API's own JSON.
     *
     * Deliberately not parsed. Every field is something a model may need to quote, the schema grows
     * on the server's schedule rather than this app's, and a DTO here would silently drop whatever
     * it had not been taught about — which in a record of someone's holdings is the worst possible
     * failure mode.
     */
    suspend fun of(personaId: String): String
}

/** [CustomerRecords] over the demo API's `/api/customers/{persona_id}`. */
class HttpCustomerRecords(
    private val calls: Call.Factory,
    private val baseUrl: String,
    private val apiKey: String,
) : CustomerRecords {

    override suspend fun of(personaId: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl/api/customers/$personaId")
            .header("Authorization", "Bearer $apiKey")
            .build()

        calls.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                throw ChatApiException(response.code, null, "customer record returned HTTP ${response.code}")
            }
            body
        }
    }
}
