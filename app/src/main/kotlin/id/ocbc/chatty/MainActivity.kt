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
import id.ocbc.chatty.customers.CustomerPickerRoute
import id.ocbc.chatty.companion.CompanionRoute
import id.ocbc.chatty.core.ai.Agent
import id.ocbc.chatty.core.avatar.AvatarController
import id.ocbc.chatty.core.ui.theme.ChattyTheme
import javax.inject.Inject
import id.ocbc.chatty.core.ai.Brain
import id.ocbc.chatty.core.ai.Language
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext

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
 * Either a customer has been picked or they have not. A `NavHost` here would exist to hold a back
 * stack one entry deep, and `BackHandler` says the same thing in one line.
 *
 * The screen is the customer picker rather than an agent list: picking a customer resolves the
 * advisor who holds that customer's record — see [id.ocbc.chatty.customers.Customer] — so the two
 * decisions the old list asked for are one tap.
 *
 * The [SharedTransitionLayout] around them is what lets the customer's portrait travel from its row
 * in the list towards the middle of the stage, so the second screen reads as somewhere the first one
 * led rather than as a cut to an unrelated app.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun ChattyApp() {
    var chosen by remember { mutableStateOf<Agent?>(null) }

    // Which model answers. Chosen on the picker beside the customer and held here rather than in the
    // companion, so it survives leaving a conversation and starting another — picking a model is a
    // decision about the session, not about one conversation.
    var brain by rememberSaveable { mutableStateOf(Brain.Default) }

    // Which language the app is in — the words on screen and the language a conversation starts in.
    //
    // Held at the root for the same reason [brain] is, and for one more: it outlives every screen.
    // The switch is on the picker, on the stage and in the thread, and all three are the same
    // decision, so there is one value rather than one per screen. Seeded from the handset once; the
    // switch owns it after that. See [ProvideAppLanguage] for why this is not the conversation's own
    // language, which is allowed to follow the model's answer.
    val context = LocalContext.current
    var language by rememberSaveable { mutableStateOf(deviceLanguage(context)) }
    val toggleLanguage = { language = language.toggled() }

    ProvideAppLanguage(language) {
        SharedTransitionLayout {
            AnimatedContent(
                targetState = chosen,
                transitionSpec = {
                    fadeIn(tween(SCREEN_FADE_MS)) togetherWith fadeOut(tween(SCREEN_FADE_MS))
                },
                label = "screen",
            ) { agent ->
                CompositionLocalProvider(
                    LocalSharedTransitionScope provides this@SharedTransitionLayout,
                    LocalAnimatedVisibilityScope provides this@AnimatedContent,
                ) {
                    if (agent == null) {
                        CustomerPickerRoute(
                            brain = brain,
                            onBrainChange = { brain = it },
                            language = language,
                            onToggleLanguage = toggleLanguage,
                            onSelect = { chosen = it },
                        )
                    } else {
                        // No BackHandler here. The companion needs to step out of a mode first, and
                        // to close its billed provider session on the way out — a handler at this
                        // level would skip both, which is exactly the bug it used to cause.
                        CompanionRoute(
                            agent = agent,
                            brain = brain,
                            language = language,
                            onToggleLanguage = toggleLanguage,
                            onBack = { chosen = null },
                        )
                    }
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
