package id.ocbc.chatty.customers

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import id.ocbc.chatty.R

/**
 * One of the people this demo can be. Picking a customer is what starts a conversation.
 *
 * # Why a customer and not an agent
 *
 * The demo API keys everything it knows about someone's money by *persona id* — see
 * [id.ocbc.chatty.core.ai.CustomerRecords]. So a persona is really a pairing: a customer with a
 * particular financial life, and the advisor who has been briefed on it. The customer is the half
 * with a face and a story, and it is the half a person recognises themself in, which is why it is
 * the half the picker shows.
 *
 * [personaId] is the join. It is the agent id the chat API returns, so tapping a row resolves to
 * that agent and the conversation opens with the advisor who holds this customer's record.
 *
 * ```
 * val customer = Customers.List.first { it.id == "rendra" }
 * val agent = repository.agent(customer.personaId)   // "alvin"
 * ```
 *
 * # Why the copy is in resources and the name is not
 *
 * [description] and [traits] are prose, and prose is translated — the platform picks the right
 * `values-<locale>` for whatever language the handset is set to, with no code deciding anything. The
 * name is a proper noun: Rendra is Rendra in both languages, and a second copy of it in a second
 * file is a thing that can drift apart with nothing gained.
 */
data class Customer(
    /** Stable key for this row, and for the shared-element transition into the stage. */
    val id: String,

    /** The agent id this customer's record lives under, and the advisor the conversation opens with. */
    val personaId: String,

    /** Proper noun, the same in every language. */
    val name: String,

    /** The portrait, from the design canvas. */
    @param:DrawableRes val photo: Int,

    /** One sentence on who they are and what they are trying to do with their money. */
    @param:StringRes val description: Int,

    /**
     * Three words for how this person approaches money.
     *
     * The first is shown in the accent colour, as the design draws it: it is the trait that sets
     * this customer apart from the other two, and leading with it means the three rows can be told
     * apart at a glance instead of by reading all three sentences.
     */
    val traits: List<Int>,
)

/**
 * The customers this build offers, in the order the design lists them.
 *
 * A fixed catalogue rather than a fetch, because these three are the demo: their portraits are
 * bundled and their copy is translated, neither of which a server could hand over. The *records*
 * behind them are still the API's, fetched per [Customer.personaId] when a conversation starts.
 */
object Customers {

    /**
     * Rendra → `alvin`, Alya → `emma`, Michael → `ayu`.
     *
     * The ids are the deployment's, not ours, and they move: `alvin` and `ayu` were `daniel` and
     * `sophia` until the service renamed them, at which point two of the three cards went grey
     * because the picker could no longer find their agent in the model list. Checked against
     * `GET /v1/models` and `GET /api/customers/{id}`, which carries the customer name each persona
     * is built around — that record is what makes this a mapping rather than a guess.
     */
    val List: kotlin.collections.List<Customer> = listOf(
        Customer(
            id = "rendra",
            personaId = "alvin",
            name = "Rendra",
            photo = R.drawable.customer_rendra,
            description = R.string.customer_rendra_description,
            traits = listOf(
                R.string.customer_rendra_trait_1,
                R.string.customer_rendra_trait_2,
                R.string.customer_rendra_trait_3,
            ),
        ),
        Customer(
            id = "alya",
            personaId = "emma",
            name = "Alya",
            photo = R.drawable.customer_alya,
            description = R.string.customer_alya_description,
            traits = listOf(
                R.string.customer_alya_trait_1,
                R.string.customer_alya_trait_2,
                R.string.customer_alya_trait_3,
            ),
        ),
        Customer(
            id = "michael",
            personaId = "ayu",
            name = "Michael",
            photo = R.drawable.customer_michael,
            description = R.string.customer_michael_description,
            traits = listOf(
                R.string.customer_michael_trait_1,
                R.string.customer_michael_trait_2,
                R.string.customer_michael_trait_3,
            ),
        ),
    )
}
