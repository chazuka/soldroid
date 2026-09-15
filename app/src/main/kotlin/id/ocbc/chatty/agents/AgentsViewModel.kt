package id.ocbc.chatty.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.ocbc.chatty.core.ai.Agent
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import id.ocbc.chatty.core.ai.Brain
import id.ocbc.chatty.core.ai.Brains
import id.ocbc.chatty.core.ai.ChatClient

/**
 * The agent list, as the screen sees it.
 *
 * Three states and no fourth: loading, a list, or a reason there is none. A failure keeps its
 * message because the API's own is the useful one — "A valid API key is required" tells the operator
 * exactly what to fix, where "something went wrong" would not.
 */
data class AgentsUiState(
    val loading: Boolean = true,
    val agents: List<Agent> = emptyList(),
    val failure: String? = null,

    /**
     * The models this build can actually reach, in the order they are offered.
     *
     * Comes from which keys the build was given rather than from the enum, so a build with only the
     * demo API's key offers one model and hides the chooser instead of showing a button that fails.
     */
    val brains: List<Brain> = emptyList(),
)

/**
 * Loads the agent list once on creation and exposes it as [state] for [AgentListRoute].
 *
 * There is no per-agent state here — just the list — so this view model is only ever alive while the
 * first screen is on screen.
 */
@HiltViewModel
class AgentsViewModel @Inject constructor(
    private val repository: AgentRepository,
    private val brains: Brains,
) : ViewModel() {

    private val _state = MutableStateFlow(AgentsUiState())
    val state: StateFlow<AgentsUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** Fetches the agent list from [repository], moving [state] through loading to a result or a failure. */
    fun load() {
        // The models are known from the build's keys, not from the network, so they are in the state
        // from the first frame — the chooser does not flicker in behind the agent list.
        val offered = brains.available
        _state.value = AgentsUiState(loading = true, brains = offered)
        viewModelScope.launch {
            _state.value = runCatching { repository.agents() }.fold(
                onSuccess = { AgentsUiState(loading = false, agents = it, brains = offered) },
                onFailure = {
                    AgentsUiState(
                        loading = false,
                        failure = it.message ?: "could not load agents",
                        brains = offered,
                    )
                },
            )
        }
    }
}
