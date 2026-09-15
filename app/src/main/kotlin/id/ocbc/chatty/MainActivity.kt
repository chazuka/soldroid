package id.ocbc.chatty

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import id.ocbc.chatty.agents.AgentListRoute
import id.ocbc.chatty.companion.CompanionRoute
import id.ocbc.chatty.core.ai.Agent
import id.ocbc.chatty.core.avatar.AvatarController
import id.ocbc.chatty.core.ui.theme.ChattyTheme
import javax.inject.Inject
import id.ocbc.chatty.core.ai.Brain
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * The app's one and only screen host.
 *
 * There is no multi-activity navigation here — [ChattyApp] switches between the agent list and the
 * companion screen itself — so this class's job is everything a Composable cannot do on its own:
 * wiring Hilt injection, drawing edge-to-edge, and tying the avatar's lifecycle to whether the app is
 * actually visible.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * Injected here, not to draw anything, but to silence the avatar.
     *
     * Audio follows the conversation rather than the window: a companion is a call, and a call does
     * not go quiet because the screen slept. A genuine audio-focus loss — an incoming call, another
     * app taking the speaker — still silences it, and leaving the conversation closes the session.
     */
    @Inject lateinit var avatar: AvatarController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ChattyTheme {
                // The stage is full-bleed by design — the avatar runs under the status bar — so no
                // insets are consumed here; each screen takes the ones its own furniture needs.
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ChattyApp()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // The controller is a process singleton holding one native PeerConnectionFactory; releasing
        // it here is what keeps a finished Activity from leaving an EGL context behind.
        if (isFinishing) avatar.release()
    }
}

/**
 * Two screens, and no navigation graph.
 *
 * Either an agent is chosen or it is not. A `NavHost` here would exist to hold a back stack one
 * entry deep, and `BackHandler` says the same thing in one line.
 *
 * The [SharedTransitionLayout] around them is what lets the agent's monogram travel from its row in
 * the list to the middle of the stage, so the second screen reads as somewhere the first one led
 * rather than as a cut to an unrelated app.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ChattyApp() {
    var chosen by remember { mutableStateOf<Agent?>(null) }

    // Which model answers. Chosen on the list beside the agent and held here rather than in the
    // companion, so it survives leaving a conversation and starting another — picking a model is a
    // decision about the session, not about one agent.
    var brain by rememberSaveable { mutableStateOf(Brain.Default) }

    SharedTransitionLayout {
        AnimatedContent(
            targetState = chosen,
            transitionSpec = { fadeIn(tween(SCREEN_FADE_MS)) togetherWith fadeOut(tween(SCREEN_FADE_MS)) },
            label = "screen",
        ) { agent ->
            CompositionLocalProvider(
                LocalSharedTransitionScope provides this@SharedTransitionLayout,
                LocalAnimatedVisibilityScope provides this@AnimatedContent,
            ) {
                if (agent == null) {
                    AgentListRoute(
                        brain = brain,
                        onBrainChange = { brain = it },
                        onSelect = { chosen = it },
                    )
                } else {
                    // No BackHandler here. The companion needs to step out of a mode first, and to
                    // close its billed provider session on the way out — a handler at this level
                    // would skip both, which is exactly the bug it used to cause.
                    CompanionRoute(agent = agent, brain = brain, onBack = { chosen = null })
                }
            }
        }
    }
}

/**
 * Short. The monogram's flight carries the motion; a long cross-fade under it would only make the
 * two screens look like they are arguing.
 */
private const val SCREEN_FADE_MS = 180
