package id.ocbc.chatty.companion

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import id.ocbc.chatty.LanguagePill
import id.ocbc.chatty.R
import id.ocbc.chatty.core.ai.Agent
import id.ocbc.chatty.core.ai.Language
import id.ocbc.chatty.core.ai.TranscriptEntry
import id.ocbc.chatty.core.ai.TurnPhase
import id.ocbc.chatty.core.ui.theme.Spacing
import id.ocbc.chatty.core.ui.theme.onSurfaceSecondary
import kotlinx.coroutines.delay
import id.ocbc.chatty.core.avatar.rememberAvatarRenderTarget
import id.ocbc.chatty.core.ai.Brain
import androidx.compose.ui.platform.LocalView
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The conversation, in whichever of its three full-screen modes the customer has chosen.
 *
 * The provider session is opened once and survives every mode switch; see [CompanionMode] for why
 * the modes are whole screens, and [VoiceStage] for why video and voice share one composition.
 *
 * [onBack] is the caller's way out, not a raw navigation pop: this composable already intercepts the
 * system back gesture to step out of a mode before leaving (see the `BackHandler` below), and it only
 * calls [onBack] after [CompanionViewModel.close] has actually stopped the provider session — skipping
 * that step is what used to leak billed sessions when back was handled one level up instead.
 *
 * ```
 * // Swap the agent argument for null to go back to a picker screen; CompanionRoute opens a fresh
 * // session for whichever agent it is given and closes it again when onBack fires.
 * if (agent != null) {
 *     CompanionRoute(agent = agent, onBack = { agent = null })
 * }
 * ```
 */
@Composable
fun CompanionRoute(
    agent: Agent,
    brain: Brain,
    language: Language,
    onToggleLanguage: () -> Unit,
    onBack: () -> Unit,
    viewModel: CompanionViewModel = hiltViewModel(),
    signals: ConversationSignals = hiltViewModel<ConversationSignalsHolder>().signals,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(CompanionMode.VIDEO) }

    // Keyed on the id so a configuration change does not tear down and re-open a billed session.
    LaunchedEffect(agent.id, brain) { viewModel.open(agent, brain) }

    // The switch is the app's, so the conversation is told about it rather than owning it. The
    // conversation may still move its own language from here — an answer that comes back in the
    // other language takes the voice and the speller with it — and that never travels back up.
    LaunchedEffect(language) { viewModel.setLanguage(language) }

    // Text mode is meant to be read. An answer arriving out loud over a thread is startling, and on
    // a phone in public it is worse than startling — so playout is suppressed for as long as the
    // thread is open, and restored on the way out without disturbing a mute the customer set.
    DisposableEffect(mode) {
        viewModel.avatar.setPlaybackSuppressed(mode == CompanionMode.TEXT)
        onDispose { viewModel.avatar.setPlaybackSuppressed(false) }
    }

    val context = LocalContext.current

    val leave = {
        viewModel.close()
        onBack()
    }

    // Whether this conversation is allowed to carry on once the screen is gone.
    //
    // It may only do so if it can *say* it is doing so. The notification is not a formality: it is
    // the only thing a customer can see once they have left the app, and the only place the "Akhiri"
    // action lives. Without it the app would be holding a microphone in the background with nothing
    // on screen and no way to stop — which is worse than the problem being solved.
    //
    // So the permission is asked for once, at the point it becomes relevant, and the answer decides
    // the behaviour rather than being ignored.
    var mayRunInBackground by rememberSaveable {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> mayRunInBackground = granted }

    LaunchedEffect(Unit) {
        if (!mayRunInBackground && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val agentName = state.agent?.displayName.orEmpty()
    // Keyed on the language too: the notification is built once from whatever it is told, so a
    // customer who switches language mid-conversation would otherwise be left with a lock screen
    // still written in the one they just left.
    DisposableEffect(context, agentName, mayRunInBackground, language) {
        if (mayRunInBackground && agentName.isNotEmpty()) {
            ConversationService.start(context, agentName, language)
        }
        onDispose { ConversationService.stop(context) }
    }

    // Without that permission the conversation ends when the customer leaves, rather than continuing
    // somewhere they cannot see or stop it. Less capable, and the right way round.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mayRunInBackground) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                if (!mayRunInBackground) leave()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    // The notification's stop action lands here rather than in the service, because ending a
    // conversation means closing a billed provider session and only this screen owns one.
    LaunchedEffect(signals) {
        signals.stop.collect { leave() }
    }

    // Every back press is handled here, and nowhere else. Back steps out of a mode first, and
    // leaving the conversation goes through `close()` so the billed provider session actually stops
    // — a handler at the Activity level used to skip that and leave sessions running.
    BackHandler {
        if (mode != CompanionMode.VIDEO) mode = CompanionMode.VIDEO else leave()
    }

    CompanionScreen(
        state = state,
        mode = mode,
        controller = viewModel.avatar,
        // Routed through the view model rather than assigned straight, because opening the thread
        // ends the answer that is playing and switches handsfree off — see
        // [CompanionViewModel.enterTextMode]. It sits on the callback and not in an effect keyed on
        // `mode` so that restoring a saved mode after a configuration change cannot re-fire it.
        onModeChange = { next ->
            if (next == CompanionMode.TEXT) viewModel.enterTextMode()
            mode = next
        },
        onAsk = viewModel::ask,
        onSpeculate = viewModel::speculate,
        onRetry = viewModel::retry,
        onInterrupt = viewModel::interrupt,
        onSpeechProblem = viewModel::onSpeechProblem,
        onToggleMute = viewModel::toggleMute,
        onToggleHandsfree = viewModel::toggleHandsfree,
        onHandsfreeTimedOut = viewModel::handsfreeTimedOut,
        onMicHint = viewModel::micNeedsAHold,
        onToggleLanguage = onToggleLanguage,
        onToggleTrace = viewModel::toggleTrace,
        onNoticeShown = viewModel::onNoticeShown,
        onBack = leave,
    )
}

/**
 * Dispatches to whichever surface the mode names, and owns the things every surface shares: the
 * snackbar, and the one speech recogniser they all press.
 */
@Composable
// The Scaffold's content padding is ignored on purpose, and lint cannot tell that from an
// oversight: `contentWindowInsets` is zeroed just below, so there is no inset for the padding to
// carry. Each surface applies its own — the stage runs under the status bar and the thread does not
// — and taking the Scaffold's would letterbox the stage.
@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
private fun CompanionScreen(
    state: CompanionUiState,
    mode: CompanionMode,
    controller: id.ocbc.chatty.core.avatar.AvatarController,
    onModeChange: (CompanionMode) -> Unit,
    onAsk: (question: String, listenedMs: Long?) -> Unit,
    onSpeculate: (partialQuestion: String) -> Unit,
    onRetry: () -> Unit,
    onInterrupt: () -> Unit,
    onSpeechProblem: (SpeechProblem) -> Unit,
    onToggleMute: () -> Unit,
    onToggleHandsfree: () -> Unit,
    onHandsfreeTimedOut: () -> Unit,
    onMicHint: () -> Unit,
    onToggleLanguage: () -> Unit,
    onToggleTrace: () -> Unit,
    onNoticeShown: () -> Unit,
    onBack: () -> Unit,
) {
    // Above the mode branch on purpose. The video sink lives as long as this conversation, so
    // opening the thread and coming back re-parents a view that has been receiving frames the whole
    // time instead of waiting on a keyframe for a new one — which is what made "show face" take
    // nearly four seconds to show a face.
    //
    // Black, to match the bundled stills exactly: the card reads as a framed portrait on the slate
    // gradient, and the still-to-live swap has no background change to give it away.
    // Portrait draws the avatar edge to edge — canvas 14B — so the picture fills its frame and the
    // crop is the point rather than a compromise: on a handset taller than the stream it takes the
    // sides, not the head.
    //
    // Landscape is the other way round and filling there is a bug, not a trade. The picture area
    // turns wide and short, and a 9:16 stream filling it is cropped to a neck and a collar — the
    // face, which is the whole reason this mode exists, ends up above the frame. Measured on a
    // rotated handset, not reasoned about. So landscape fits instead and lets the slate show at the
    // sides, which is the same bargain [AvatarSurface] documents: a letterbox nobody sees beats a
    // crop everybody does.
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val face = rememberAvatarRenderTarget(
        controller = controller,
        background = Color.Black,
        crop = portrait,
    )

    // Used to tell "I heard nothing" apart from "there is no network" — see the recogniser's
    // onProblem below.
    val context = LocalContext.current

    val snackbars = remember { SnackbarHostState() }
    val notice = state.notice
    val noticeText = notice?.let { stringResource(it.message) }
    LaunchedEffect(notice?.id) {
        if (noticeText != null) {
            snackbars.showSnackbar(noticeText)
            onNoticeShown()
        }
    }

    // When the customer was last heard. Handsfree gives up on a clock rather than on a count of
    // failed listens: a recogniser that returns "no match" in under two seconds would otherwise end
    // handsfree three answers' worth of thinking time later, which is exactly when someone is most
    // likely to still be composing their next question.
    // Seeded with the current time, never zero. As an epoch, zero reads as decades of silence, so
    // the quiet clock would answer "give up" the instant handsfree was switched on — before the
    // effect that seeds it had a chance to run. Handsfree turned itself off in the same frame the
    // customer turned it on, which looked exactly like the button not working.
    var lastHeardAtMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Bumped to ask for another listen. `acceptingInput` already flips false-then-true across a
    // turn, which re-arms after every answer; this covers the case it cannot see — a listen that
    // ended in silence, where no turn ever ran and so no state changed.
    var rearm by remember { mutableIntStateOf(0) }

    /** Extra pause before the next listen, so a failing recogniser is retried rather than hammered. */
    var micRetryDelayMs by remember { mutableLongStateOf(0L) }

    val speech = rememberSpeechInput(
        onResult = { question, listenedMs ->
            lastHeardAtMs = System.currentTimeMillis()
            micRetryDelayMs = 0L
            Log.i(HANDSFREE_TAG, "heard a question of ${question.length} chars in ${listenedMs}ms")
            onAsk(question, listenedMs)
            // Ask for another listen whatever came of that one.
            //
            // A question usually starts a turn, and the turn is what re-arms the microphone: it
            // takes `acceptingInput` false and then true again, and that is a key of the effect
            // below. A question that starts no turn has no such edge — the agent's own voice
            // arriving back through the microphone, an empty transcript, a question while the
            // previous turn is somehow still running — and handsfree simply stopped, showing
            // READY, listening to nothing, until something else happened to recompose the screen.
            //
            // Asking here costs nothing when a turn did start: the effect re-runs, sees a turn in
            // flight, and closes the microphone exactly as it would have.
            rearm += 1
        },
        onProblem = { problem ->
            // Both decisions belong to [Handsfree]: what the failure really was, and what to do
            // about it. See that file for why the rule is "retry unless waiting cannot help".
            val actual = Handsfree.classify(problem, context.hasNetwork())
            val recovery = Handsfree.recover(actual, on = state.handsfree)
            recovery.notify?.let(onSpeechProblem)
            if (recovery.rearm) {
                micRetryDelayMs = recovery.backoffMs
                rearm += 1
            }
        },
        languageTag = state.listenFor.tag,
    )
    val listening by speech.listening
    val partial by speech.partial

    // The microphone belongs to the stage. Text mode has a keyboard and a live session underneath
    // it, and a microphone still listening behind that sheet would answer a question the customer
    // was not asking — which is how the avatar ended up talking over text mode before.
    val onStage = mode.isStage

    // The display is held awake only while the face is on it, because that is the only mode where
    // the screen is the point. Voice and handsfree are allowed to go dark — they keep working.
    val view = LocalView.current
    DisposableEffect(view, mode) {
        view.keepScreenOn = mode == CompanionMode.VIDEO
        onDispose { view.keepScreenOn = false }
    }

    // When the microphone button went down, so a press too short to be speech can be told from a
    // real one. Two things produce those: a stray tap on a large round button, and the first tap of
    // the double tap that toggles handsfree. Both used to submit a fraction of a second of audio and
    // earn the customer a "not caught" message for something they never said.
    var micPressedAtMs by remember { mutableLongStateOf(0L) }

    val onMicPress: () -> Unit = {
        if (state.handsfree) {
            // The listen is already running; a press means "I have finished talking". With nothing
            // transcribed yet there is nothing to finish, and ending it would only cost a re-arm.
            if (partial.isNotEmpty()) speech.stop()
        } else {
            micPressedAtMs = System.currentTimeMillis()
            speech.start()
        }
    }

    // Pressing the microphone and letting go achieves nothing on its own — the press was too short
    // to be speech, and a double tap needs a second tap inside about a third of a second. Both are
    // easy to get wrong, and both currently end in silence. Say what the button wants instead.
    val onMicTap: () -> Unit = {
        if (Handsfree.release(state.handsfree, System.currentTimeMillis() - micPressedAtMs) ==
            Handsfree.Release.DISCARD
        ) {
            onMicHint()
        }
    }

    val onMicRelease: () -> Unit = {
        when (Handsfree.release(on = state.handsfree, heldMs = System.currentTimeMillis() - micPressedAtMs)) {
            Handsfree.Release.SUBMIT -> speech.stop()
            Handsfree.Release.DISCARD -> speech.cancel()
            Handsfree.Release.IGNORE -> Unit
        }
    }

    // Whether the microphone should be open at this instant. The agent speaks through the same
    // handset the microphone is in, so `acceptingInput` belongs in here rather than in a branch: the
    // microphone is shut for the whole of a turn, not merely not re-opened.
    // The avatar going quiet is a reason to re-evaluate, so it is part of the condition rather than
    // something the effect would have to be woken for.
    val wantsMic = state.handsfree && onStage && state.acceptingInput &&
        !state.avatarSpeaking && !state.reconnecting && !state.avatarOpening

    // Handsfree can be switched on before any question has ever been heard this session, when
    // `lastHeardAtMs` is still its initial zero. Reset it here so the quiet clock below starts
    // counting from now rather than from the epoch, which would read as "already quiet for ages".
    // Restarted whenever handsfree is switched on and whenever the stage comes back, because the
    // clock below measures silence *while the microphone could have been listening*. The thread
    // suspends it: someone reading an answer for a minute has not gone quiet on the agent, and
    // switching handsfree off at them for it — with a notice blaming the room — is wrong twice.
    LaunchedEffect(state.handsfree, onStage) {
        if (state.handsfree && onStage) lastHeardAtMs = System.currentTimeMillis()
    }

    // One effect, deliberately. An earlier version split "open the microphone" and "close the
    // microphone" across two effects, and lost the race between them: the close ran, then a rearm
    // already in flight opened the microphone again a beat later, leaving it listening with
    // handsfree switched off. Keyed on a single condition, Compose cancels the pending open before
    // it can happen.
    //
    // [speech] is a key because it is replaced whenever the listening language changes, and the
    // condition above it is not: handsfree stays on, the stage stays up, and the turn machine stays
    // idle. Without it the switch left a live conversation with a microphone that never opened
    // again — nothing looked broken, and nothing was heard.
    //
    // A listen already running is left alone. Cancelling it to restart in the new language sounds
    // tidier and was worse: the language follows the *answer* as well as the switch, so it changes
    // between turns on its own, and cancelling took the customer's half-spoken question with it —
    // `startListening` against a session being torn down returns ERROR_CLIENT, which cost a
    // recovery backoff and several seconds of a microphone that looked open and heard nothing.
    // [SpeechInput.start] declines while one is in flight, so the listen finishes in the language
    // it began in and the next one picks up the new one.
    LaunchedEffect(wantsMic, rearm, speech) {
        when (val intent = Handsfree.intent(
            on = state.handsfree,
            onStage = onStage,
            accepting = state.acceptingInput,
            avatarSpeaking = state.avatarSpeaking,
            reconnecting = state.reconnecting,
            avatarOpening = state.avatarOpening,
            quietMs = System.currentTimeMillis() - lastHeardAtMs,
            retryDelayMs = micRetryDelayMs,
        )) {
            is Handsfree.Intent.Close -> {
                Log.i(HANDSFREE_TAG, "microphone closed: ${intent.because}")
                speech.cancel()
            }
            is Handsfree.Intent.GiveUp -> {
                Log.i(HANDSFREE_TAG, "handsfree off: nothing heard for ${Handsfree.QUIET_MS}ms")
                onHandsfreeTimedOut()
            }
            is Handsfree.Intent.Listen -> {
                // The recogniser's own cooling-off period is added here rather than enforced inside
                // start(). A start that refuses silently is a dead end: nothing changes, so this
                // effect is never re-run, and handsfree sits on screen saying "on" over a
                // microphone that will never open again. Waiting costs a later listen; refusing
                // cost the whole conversation.
                delay(intent.delayMs + speech.restartDelayMs)
                Log.i(HANDSFREE_TAG, "handsfree listening")
                speech.start(endpointed = true)
            }
        }
    }

    // The one invariant handsfree has, checked on a clock rather than trusted.
    //
    // Everything above is edge-driven: an effect re-runs when a key changes, opens the microphone,
    // and finishes. That is correct right up until some path leaves no key to change, and then
    // handsfree sits on screen saying "on" over a microphone that will never open again. It has
    // happened four times, each from a different cause — a listener wiped by recomposition, an
    // early return that produced no turn, a language key missing from an effect, a start that
    // refused silently — and each was found by a customer rather than by the app.
    //
    // So the state is also checked level-triggered: if handsfree wants the microphone open and it
    // is shut, that is a fault whatever caused it, and bumping [rearm] re-runs the effect that
    // opens it. The period is comfortably longer than the longest legitimate wait before a listen
    // (re-arm plus a fault backoff plus the recogniser's cooling-off), so a listen that is merely
    // on its way is never mistaken for a stall.
    val stateHandsfree by rememberUpdatedState(state.handsfree)
    val micWanted by rememberUpdatedState(wantsMic)
    val micOpen by rememberUpdatedState(listening)
    val micGates by rememberUpdatedState(
        "on=${state.handsfree} stage=$onStage accepting=${state.acceptingInput} " +
            "speaking=${state.avatarSpeaking} reconnecting=${state.reconnecting} " +
            "opening=${state.avatarOpening}",
    )
    LaunchedEffect(Unit) {
        while (true) {
            delay(HANDSFREE_WATCHDOG_MS)
            if (micOpen) continue
            if (micWanted) {
                Log.w(HANDSFREE_TAG, "handsfree wanted the microphone and it was shut; re-arming")
                rearm += 1
            } else if (stateHandsfree) {
                // Handsfree is on and the microphone is shut on purpose. Which gate is holding it
                // is the first question every time this is reported, and until now the log could
                // not answer it: the branch that names a reason only runs when the gates *change*,
                // and a conversation stuck this way is one where nothing changes at all.
                Log.i(HANDSFREE_TAG, "microphone shut while handsfree is on: $micGates")
            }
        }
    }

    // End of question, decided here rather than by the recogniser.
    //
    // `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS` is a hint, and on the handset this was
    // built against it is ignored outright: the microphone stayed open past thirty seconds,
    // transcribing the room, and never delivered a result. Handsfree cannot be built on a hint.
    //
    // The partial transcript is the signal that does work everywhere. This effect is keyed on it, so
    // every new word restarts the wait; the delay only runs to completion when nothing new has
    // arrived for [HANDSFREE_SETTLE_MS], which is what "they have stopped talking" looks like from
    // here. Then we close the utterance ourselves and the recogniser delivers what it heard.
    LaunchedEffect(state.handsfree, onStage, listening, partial) {
        if (!state.handsfree || !onStage || !listening || partial.isEmpty()) return@LaunchedEffect

        // Partway through the pause, hand the question to the model on the strength of the partial
        // transcript. The rest of this wait — and the recogniser's own finalising after it — then
        // happens while an answer is already being written.
        //
        // This effect restarts on every new word, so a customer who is still talking cancels the
        // speculation before it is sent and the one that eventually goes is the one they stopped
        // on. Nothing is shown or spoken from it unless the finished transcript agrees; see
        // [CompanionViewModel.speculate].
        delay(HANDSFREE_SPECULATE_AFTER_MS)
        Log.i(HANDSFREE_TAG, "speculating after ${HANDSFREE_SPECULATE_AFTER_MS}ms of quiet")
        onSpeculate(partial)

        delay(HANDSFREE_SETTLE_MS - HANDSFREE_SPECULATE_AFTER_MS)
        Log.i(HANDSFREE_TAG, "end of question: ${HANDSFREE_SETTLE_MS}ms with no new words")
        speech.stop()
    }

    // The same problem from the other side: a room with noise in it but no speech produces no
    // partial at all, so the effect above never arms and the microphone would stay open for as long
    // as the screen is up. This closes it and asks for a fresh listen; it is the quiet clock above,
    // not a count of attempts, that eventually ends handsfree if the room stays quiet.
    LaunchedEffect(state.handsfree, onStage, listening, rearm) {
        if (!state.handsfree || !onStage || !listening) return@LaunchedEffect
        delay(HANDSFREE_MAX_LISTEN_MS)
        Log.i(HANDSFREE_TAG, "listen hit the ${HANDSFREE_MAX_LISTEN_MS}ms cap")
        if (partial.isEmpty()) {
            speech.cancel()
        } else {
            // Something was said, and the settle timer will have fired by now unless the customer
            // has been talking without pause for the whole window. Take what there is.
            speech.stop()
        }
        rearm += 1
    }

    // One small buzz when an answer lands. It is the only moment on this screen worth feeling: the
    // customer may have looked away during the wait, and this brings them back without a sound.
    // Keyed on how many answers exist rather than on the phase — the phase reaches SPEAKING on every
    // provider event, which would buzz them through the whole reply.
    val haptics = LocalHapticFeedback.current
    val answers = state.transcript.count { !it.fromCustomer() }
    LaunchedEffect(answers) {
        if (answers > 0) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        // Every surface handles its own insets: the stage runs under the status bar, the thread does
        // not, and a Scaffold inset would letterbox the first of those.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            if (mode.isStage) {
                VoiceStage(
                    state = state,
                    mode = mode,
                    face = face,
                    listening = listening,
                    partial = partial,
                    micAvailable = speech.available,
                    onPress = onMicPress,
                    onRelease = onMicRelease,
                    onInterrupt = onInterrupt,
                    onRetry = onRetry,
                    onOpenText = { onModeChange(CompanionMode.TEXT) },
                    onToggleVideo = {
                        onModeChange(
                            if (mode == CompanionMode.VIDEO) CompanionMode.VOICE else CompanionMode.VIDEO,
                        )
                    },
                    onToggleMute = onToggleMute,
                    onToggleHandsfree = onToggleHandsfree,
                    onToggleLanguage = onToggleLanguage,
                    onMicTap = onMicTap,
                    onToggleTrace = onToggleTrace,
                    // Stop ends the conversation, which is what leaving this screen has always
                    // meant: close the billed provider session, then go back.
                    onStop = onBack,
                )
            } else {
                TextMode(
                    state = state,
                    onAsk = onAsk,
                    onSpeculate = onSpeculate,
                    onRetry = onRetry,
                    onShowFace = { onModeChange(CompanionMode.VIDEO) },
                    onToggleLanguage = onToggleLanguage,
                )
            }
        }
    }
}

/**
 * Text mode: the whole conversation, read rather than heard.
 *
 * This is the design's chat thread — a solid header over a light canvas, the exchange as tailed
 * bubbles, and a composer pill between two circular controls. It is an opaque sheet over the avatar
 * rather than a separate screen, because the session underneath is still live: a question sent here
 * can be watched being answered by going back to the face.
 *
 * It draws its own header instead of using the shared floating one, because a bar designed to sit
 * over a photograph looks wrong over paper.
 */
@Composable
private fun TextMode(
    state: CompanionUiState,
    onAsk: (question: String, listenedMs: Long?) -> Unit,
    onSpeculate: (partialQuestion: String) -> Unit,
    onRetry: () -> Unit,
    onShowFace: () -> Unit,
    onToggleLanguage: () -> Unit,
) {
    var draft by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Follow the conversation, including as the draft answer grows: a line that arrives off-screen
    // may as well not have arrived.
    LaunchedEffect(state.transcript.size, state.draft) {
        val last = state.transcript.size + if (state.draft.isNullOrEmpty()) -1 else 0
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            ThreadHeader(
                state = state,
                onShowFace = onShowFace,
                onToggleLanguage = onToggleLanguage,
            )

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    // `widthIn` first: `fillMaxWidth` hands the child a fixed width, and a cap
                    // applied after that has nothing left to cap.
                    .widthIn(max = THREAD_MAX_WIDTH)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally),
                state = listState,
                contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.lg),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                item {
                    Text(
                        text = stringResource(R.string.companion_today),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                items(state.transcript) { entry: TranscriptEntry ->
                    Bubble(text = entry.text, fromCustomer = entry.fromCustomer())
                }
                if (!state.draft.isNullOrEmpty()) {
                    item { Bubble(text = state.draft, fromCustomer = false, streaming = true) }
                }
                // The answer is still being composed and has produced nothing yet.
                if (state.phase == TurnPhase.THINKING && state.draft.isNullOrEmpty()) {
                    item { TypingBubble() }
                }
                // The turn failed and nothing came back. The question is still the last thing in the
                // thread, so the way out sits directly under it.
                if (state.retryable != null) {
                    item { RetryRow(enabled = state.acceptingInput, onRetry = onRetry) }
                }
                // Shortcuts only while the thread is empty. Once there is a conversation they are
                // clutter: the customer has already shown they know what to ask.
                if (state.transcript.isEmpty() && state.draft.isNullOrEmpty()) {
                    item { Starters(enabled = state.acceptingInput, onPick = { onAsk(it, null) }) }
                }
            }

            Composer(
                state = state,
                draft = draft,
                onDraftChange = { draft = it },
                onSend = {
                    onAsk(draft, null)
                    draft = ""
                },
                onShowFace = onShowFace,
            )
        }
    }
}

/**
 * The way out of a failed turn, inline in the thread.
 *
 * A snackbar says what went wrong and then leaves; this stays until the customer does something
 * about it. It sits on the assistant's side because that is where the missing answer would have
 * been — the gap is what it is offering to fill.
 */
@Composable
private fun RetryRow(enabled: Boolean, onRetry: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(
            text = stringResource(R.string.turn_failed_inline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceSecondary,
        )
        Surface(
            onClick = onRetry,
            enabled = enabled,
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.primary,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
        ) {
            Text(
                text = stringResource(R.string.turn_retry),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            )
        }
    }
}

/** The thread's own header: who you are talking to, that the session is secure, and the way back. */
@Composable
private fun ThreadHeader(
    state: CompanionUiState,
    onShowFace: () -> Unit,
    onToggleLanguage: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .widthIn(max = THREAD_MAX_WIDTH)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .statusBarsPadding()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                // Tapping the mark is the way back to the face — the same affordance as the design's
                // avatar chip, doing the one thing this app has that the design does not.
                Surface(
                    onClick = onShowFace,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(HEADER_MARK_SIZE),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Face,
                            contentDescription = stringResource(R.string.companion_show_face),
                            modifier = Modifier.size(HEADER_MARK_ICON),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        text = state.agent?.displayName.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // The mode, not the security posture. Every surface's header now reads
                    // "<agent> / <where you are>" — avatar mode, voice mode, text mode — so the
                    // line means the same thing wherever the customer happens to be standing.
                    Text(
                        text = stringResource(R.string.companion_mode_text),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceSecondary,
                    )
                }
                LanguagePill(
                    onToggle = onToggleLanguage,
                    enabled = state.acceptingInput,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/**
 * The opening shortcuts.
 *
 * The design offers four transaction shortcuts; these are the four questions *these* personas can
 * actually answer from the customer record they hold, which is the same idea aimed at what this app
 * does. Tapping one sends it as if typed.
 */
@Composable
private fun Starters(enabled: Boolean, onPick: (String) -> Unit) {
    val starters = listOf(
        R.string.starter_balance,
        R.string.starter_spending,
        R.string.starter_goals,
        R.string.starter_portfolio,
    ).map { stringResource(it) }

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        starters.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                row.forEach { label ->
                    Surface(
                        onClick = { onPick(label) },
                        enabled = enabled,
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                        modifier = Modifier.weight(1f).height(STARTER_HEIGHT),
                    ) {
                        Box(
                            contentAlignment = Alignment.CenterStart,
                            modifier = Modifier.padding(horizontal = Spacing.lg),
                        ) {
                            Text(text = label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The composer: a pill between two circles, as the design draws it.
 *
 * The right-hand circle is the one control that changes meaning — it sends when there is something
 * to send and holds-to-talk when there is not, so the thumb never has to hunt for a second button.
 */
@Composable
private fun Composer(
    state: CompanionUiState,
    draft: String,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onShowFace: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier
                    .widthIn(max = THREAD_MAX_WIDTH)
                    .fillMaxWidth()
                    .align(Alignment.CenterHorizontally)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Surface(
                    onClick = onShowFace,
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(COMPOSER_CONTROL_SIZE),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Face,
                            contentDescription = stringResource(R.string.companion_show_face),
                        )
                    }
                }

                TextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    modifier = Modifier.weight(1f),
                    enabled = state.acceptingInput,
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                    placeholder = {
                        Text(
                            text = stringResource(
                                R.string.companion_compose_hint_named,
                                state.agent?.displayName.orEmpty(),
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    // The keyboard's own action sends. A single-line composer that ignores the send
                    // key is the kind of thing nobody reports and everybody feels.
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )

                Surface(
                    onClick = onSend,
                    enabled = state.acceptingInput && draft.isNotBlank(),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(COMPOSER_CONTROL_SIZE),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.companion_send),
                        )
                    }
                }
            }
        }
    }
}

private val HEADER_MARK_SIZE = 36.dp
private val HEADER_MARK_ICON = 18.dp
private val STARTER_HEIGHT = 48.dp
private val COMPOSER_CONTROL_SIZE = 44.dp

/**
 * The pause, after the last word the recogniser reported, that ends a handsfree question.
 *
 * Tuned against how the partial transcript actually arrives rather than against how long a person
 * pauses: engines report in bursts, and a window under about a second cuts people off mid-thought.
 */
/**
 * How often handsfree checks that the microphone is actually open when it should be.
 *
 * Longer than the longest legitimate wait before a listen begins — [Handsfree.REARM_MS] plus
 * [Handsfree.FAULT_BACKOFF_MS] plus the recogniser's own cooling-off, about 2.3 seconds — so a
 * listen that is merely on its way is never mistaken for a stall and restarted out from under
 * itself.
 */
private const val HANDSFREE_WATCHDOG_MS = 5_000L

private const val HANDSFREE_SETTLE_MS = 1_200L

/**
 * How much of the settle to spend before asking the model to start.
 *
 * Long enough that someone drawing breath mid-sentence has not triggered it, short enough that the
 * remaining 800ms of pause — plus however long the recogniser takes to finalise — is spent with an
 * answer already being written rather than waiting to begin.
 *
 * Getting it wrong is cheap in one direction and free in the other: too eager wastes a request,
 * too late merely speculates less often. It can never produce an answer to the wrong question,
 * because the finished transcript still has to agree before a word of it is used.
 */
private const val HANDSFREE_SPECULATE_AFTER_MS = 400L

/** The longest a single handsfree listen may run before it is closed and retried. */
private const val HANDSFREE_MAX_LISTEN_MS = 15_000L

/** Handsfree's own log tag: this loop is invisible on screen and impossible to debug without it. */
private const val HANDSFREE_TAG = "chatty.handsfree"

/**
 * How wide the thread is allowed to get.
 *
 * A conversation is reading, and reading has a comfortable measure — roughly 60 to 75 characters a
 * line. Left to fill the screen the thread was fine held upright and wrong turned sideways: on a
 * 2856px handset the starter chips stretched into bars, an answer ran the full width of the glass,
 * and the send button ended up a hand's width from the text it sends. Capped and centred, the
 * sideways screen simply gets margins.
 */
private val THREAD_MAX_WIDTH = 640.dp
