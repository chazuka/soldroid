package id.ocbc.chatty.companion

import java.io.IOException
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import id.ocbc.chatty.R
import id.ocbc.chatty.core.ai.Agent
import id.ocbc.chatty.core.ai.ChatClient
import id.ocbc.chatty.core.ai.Language
import id.ocbc.chatty.core.ai.Speaker
import id.ocbc.chatty.core.ai.SpeechSynthesizer
import id.ocbc.chatty.core.ai.TranscriptEntry
import id.ocbc.chatty.core.ai.TurnPhase
import id.ocbc.chatty.core.ai.TurnTrace
import id.ocbc.chatty.core.ai.asChatHistory
import id.ocbc.chatty.core.ai.sentences
import id.ocbc.chatty.core.avatar.AvatarController
import id.ocbc.chatty.core.avatar.AvatarEvent
import id.ocbc.chatty.core.avatar.LiveAvatarEvent
import id.ocbc.chatty.core.avatar.LiveAvatarSession
import id.ocbc.chatty.core.avatar.Utterance
import id.ocbc.chatty.di.ApplicationScope
import id.ocbc.chatty.di.AvatarSessionFactory
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import id.ocbc.chatty.core.ai.SpokenCaption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.receiveAsFlow
import id.ocbc.chatty.core.ai.Brain
import id.ocbc.chatty.core.ai.Brains
import id.ocbc.chatty.core.ai.revealCaptions
import id.ocbc.chatty.core.ai.telemetry.TurnEvent
import id.ocbc.chatty.core.ai.telemetry.TurnOutcome
import id.ocbc.chatty.core.ai.telemetry.TurnSink

/**
 * A transient line for the customer.
 *
 * It carries an [id] so the screen can show the same message twice running and have the second one
 * actually appear — two identical strings are the same value, and a snackbar keyed on the text alone
 * would swallow the repeat.
 */
data class Notice(val id: Long, @param:StringRes val message: Int)

/**
 * Everything the companion screen draws.
 *
 * [draft] is separate from [transcript] because it is still being written: the model's answer
 * appears a fragment at a time and only becomes a transcript entry once it is whole. Rendering it as
 * a live bubble is what makes the wait feel like someone talking rather than a spinner.
 */
data class CompanionUiState(
    val agent: Agent? = null,

    /**
     * Which model is answering. Chosen on the agent list and fixed for the conversation — swapping
     * brains mid-thread would make the transcript above a question and the answer below it come from
     * two different advisers.
     */
    val brain: Brain = Brain.Default,
    val phase: TurnPhase = TurnPhase.IDLE,
    val transcript: List<TranscriptEntry> = emptyList(),
    val draft: String? = null,

    /**
     * How much of the answer the avatar has actually said, revealed in step with the audio.
     *
     * [draft] is the model's pace and this is the voice's, and they are nowhere near each other: the
     * text is typically finished half a minute before the mouth is. The thread wants [draft] —
     * reading is the point there, and holding words back would just be slow. The stage wants this
     * one, because on the stage the words sit under a face that is still speaking them.
     */
    val caption: String? = null,

    /** The room is joined and a video track is arriving. False means captions carry the conversation. */
    val avatarLive: Boolean = false,
    val muted: Boolean = false,

    /**
     * The language this conversation is *answered* in: the agent's voice and the number speller.
     *
     * Follows the words actually being spoken, so a question asked in English is answered in English
     * whatever the switch says. Changing it costs nothing and is undone by the next question, which
     * is why it is allowed to move on a single utterance.
     */
    val language: Language = Language.INDONESIAN,

    /**
     * The language the *recogniser* is told to expect, which is deliberately not [language].
     *
     * # Why these are two fields
     *
     * They fail in opposite directions. Answering in the wrong language is a wrong voice on the
     * right words — ugly, and corrected by the next turn. Listening in the wrong language destroys
     * the words themselves: pinned to `id-ID`, "How much money do I have" comes back as "Oh macam
     * mana", and nothing downstream can recover a sentence that was never transcribed.
     *
     * So the answer may chase every utterance, and the ear may not. This moves only when the
     * customer says so — the switch in the header — or after they have stayed in the other language
     * long enough that it is clearly not a one-off; see [LISTEN_SWITCH_AFTER]. Between those, the
     * platform's own bilingual switching does the adapting, which is what it is for.
     */
    val listenFor: Language = Language.INDONESIAN,
    val notice: Notice? = null,

    /**
     * The question whose turn failed, kept so it can be asked again.
     *
     * A notice tells the customer something went wrong and then disappears; it does not give them a
     * way out. Holding the question means the screen can offer one button that costs nothing to
     * press — which matters most on the failure this app actually has, a network blip mid-answer.
     */
    val retryable: String? = null,

    /**
     * Continuous listening: the microphone re-opens by itself once the agent has finished speaking.
     *
     * Deliberately half-duplex. The agent's voice comes out of the same handset the microphone is
     * in, and Android's recogniser has no idea which sounds are ours — left open while the avatar
     * talks it would transcribe the agent and ask the agent about itself. So the microphone is shut
     * for the whole of a turn and re-armed at the end of it, which is also why this is a mode rather
     * than the default: it trades barge-in for not having to touch the screen.
     */
    val handsfree: Boolean = false,

    /** Where the last turn's time went. Surfaced behind a long-press, never in the customer's way. */
    val trace: TurnTrace? = null,
    val showTrace: Boolean = false,
) {
    /** One turn at a time: a second question while one is in flight would interleave two answers. */
    val acceptingInput: Boolean get() = agent != null && phase == TurnPhase.IDLE

    /** Barge-in only makes sense while there is speech left to cut off. */
    val interruptible: Boolean get() = phase == TurnPhase.SPEAKING
}

/**
 * One conversation with one agent.
 *
 * # The shape of a turn
 *
 * Three vendors, in a fixed order, overlapped as far as each one allows:
 *
 *  1. the chat API streams the answer — the only text this app sends anywhere;
 *  2. [sentences] regroups those fragments into clauses;
 *  3. ElevenLabs renders each clause to PCM as it arrives;
 *  4. LiveAvatar lip-syncs those samples, having never been shown a word of it.
 *
 * The overlap is the point. Sentence one is being spoken while the model is still writing sentence
 * three, so what the customer waits for is the first clause, not the whole answer. `agent.speak_end`
 * is still sent once, at the very end, so the provider treats the whole thing as one utterance and
 * reports one `agent.speak_ended`.
 *
 * Steps 3 and 4 are allowed to fail. If either does the answer is already in the transcript and the
 * conversation continues in text — the face is an enhancement, never the channel.
 */
@HiltViewModel
class CompanionViewModel @Inject constructor(
    private val brains: Brains,
    private val synthesizer: SpeechSynthesizer,
    private val sessions: AvatarSessionFactory,
    private val controller: AvatarController,
    private val signals: ConversationSignals,
    private val turns: TurnSink,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow(CompanionUiState())
    val state: StateFlow<CompanionUiState> = _state.asStateFlow()

    /** The face, exposed so the screen can mount [AvatarSurface] once and keep it across mode switches. */
    val avatar: AvatarController get() = controller

    private var session: LiveAvatarSession? = null
    private var sessionEvents: Job? = null
    private var refresh: Job? = null
    private var turn: Job? = null
    private var noticeSeq = 0L

    /** When the room was joined, so the wait for the first decoded frame can be measured. */
    private var attachedAtMs: Long? = null

    /** Completed when the provider reports the lips have started, which is when captions may begin. */
    private var speaking: CompletableDeferred<Unit>? = null

    /**
     * Whether a turn is actually in flight.
     *
     * The provider's speak events are not only ours: one arriving between turns used to leave the
     * screen claiming the agent was still talking, which disables the microphone and never clears —
     * the conversation looks hung while nothing is wrong. Phase is this app's state machine, so only
     * a turn this app started may move it.
     */
    private var turnInFlight = false

    /** The caption being revealed, kept so a new turn can stop the previous one mid-sentence. */
    private var captionJob: Job? = null

    /**
     * Whether the brain's briefing material was in hand before the first question.
     *
     * Recorded rather than assumed: the warm-up races the customer, and a customer who asks
     * immediately pays for the fetch the warm-up was meant to have done. Without this the
     * optimisation is unfalsifiable in the field — it only ever looks good on the bench where it
     * was measured.
     */
    private var warmed = false

    /** Whether the last turn used an answer started before the question finished. */
    private var speculated = Speculated.NONE

    /**
     * Whether the customer cut this turn short.
     *
     * Barge-in does not fail a turn — the avatar stops, the pending utterance completes, and
     * everything unwinds the ordinary way — so by the time the turn ends there is nothing left to
     * say it was interrupted. Without this flag a cut-off turn is indistinguishable from one the
     * agent finished, and the two mean opposite things: one is the feature working, the other is a
     * short answer.
     */
    private var interrupted = false

    /**
     * An answer already being generated for a question the customer has probably finished asking.
     *
     * # Why an answer is started before the question is
     *
     * Handsfree has no button to let go of, so the end of a question is a pause: the screen waits
     * for [HANDSFREE_SETTLE_MS] of no new words, then closes the utterance and the recogniser
     * finalises. All of that is dead time in which the model could already have been writing.
     *
     * So the screen asks for a speculation partway through that pause, on the partial transcript.
     * If the finished transcript says the same thing the answer is already in flight and the
     * customer gets it most of a second sooner; if it says anything else the answer is thrown away
     * unheard and the turn runs exactly as it always did.
     *
     * The discipline that makes this safe is [TurnRules.sameQuestion] and the fact that nothing is
     * shown, spoken, or added to the transcript until a turn adopts it. Being wrong costs one API
     * call, never a wrong answer.
     */
    private var speculation: Speculation? = null

    /**
     * One answer generated ahead of the question being finished.
     *
     * Fragments land in an unbounded channel rather than being collected into a string, so that
     * adopting one is just draining it: whatever arrived before adoption is replayed in order, and
     * whatever comes after continues to flow. The turn downstream cannot tell the difference
     * between this and a reply it asked for itself, which is what keeps the pipeline unchanged.
     */
    private class Speculation(
        val question: String,
        val job: Job,
        val fragments: Channel<String>,
    )

    init {
        viewModelScope.launch {
            controller.events.collect { event ->
                when (event) {
                    is AvatarEvent.Attached -> {
                        attachedAtMs?.let {
                            Log.i(TAG, "stage: first frame ${System.currentTimeMillis() - it}ms after attach")
                            attachedAtMs = null
                        }
                        _state.update { it.copy(avatarLive = true) }
                    }
                    is AvatarEvent.Failed -> _state.update {
                        it.copy(avatarLive = false, notice = notice(R.string.avatar_unavailable))
                    }
                }
            }
        }
        viewModelScope.launch {
            controller.audioMuted.collect { muted -> _state.update { it.copy(muted = muted) } }
        }

        // The notification's stop action is handled here, not on the screen.
        //
        // A conversation carries on with the app in the background — that is the point of the
        // service — and Compose stops recomposing down there, so a collector in the composable would
        // not run until the customer came back. The one moment they need this to work is the moment
        // it would not have. A view model's scope is not gated by the window, so the session, the
        // audio and the billing all stop the instant the button is pressed; the screen catches up
        // with the navigation whenever it is next looked at.
        viewModelScope.launch {
            signals.stop.collect { close() }
        }
    }

    /**
     * Opens a session for [agent]. Idempotent for the agent already open, because the screen calls it
     * from a `LaunchedEffect` that re-runs on configuration change.
     */
    fun open(agent: Agent, brain: Brain = Brain.Default) {
        if (_state.value.agent?.id == agent.id && _state.value.brain == brain) return
        closeSession()
        _state.value = CompanionUiState(
            agent = agent,
            brain = brain,
            language = _state.value.language,
        )

        viewModelScope.launch { openSession(agent) }
        // Warm the chosen brain's brief while the screen and the avatar session are still being
        // set up, so the first question does not pay for it. See [ChatClient.warm].
        warmed = false
        viewModelScope.launch {
            brains[brain].warm(agent.id)
            warmed = true
        }
        // The synthesizer's connection, opened while the screen is still settling rather than
        // inside the silence of the first answer. See [SpeechSynthesizer.warm].
        viewModelScope.launch { runCatching { synthesizer.warm() } }
        startRefreshWatchdog(agent)
    }

    /**
     * Asks the open agent a question. Ignored unless the previous turn has finished.
     *
     * [listenedMs] is how long the recogniser took to decide the question had ended — the one leg
     * of a turn that happens before the turn does. Null when the question was typed, which has no
     * listening to account for.
     */
    fun ask(question: String, listenedMs: Long? = null) {
        val current = _state.value
        val agent = current.agent ?: return
        val text = question.trim()
        if (text.isEmpty() || !current.acceptingInput) return

        // The agent's own voice, arriving back through the microphone. Dropped without a trace:
        // there is no customer to tell, nothing went wrong from their side, and a notice about it
        // would be the app explaining its own plumbing. See [TurnRules.isEcho].
        val lastAnswer = current.transcript.lastOrNull { it.speaker == Speaker.AGENT }?.text
        if (TurnRules.isEcho(text, lastAnswer)) {
            Log.i(TAG, "ignored ${text.length} chars of the agent's own voice")
            return
        }

        val history = current.transcript + TranscriptEntry(Speaker.CUSTOMER, text)
        // The language of the *question*, adopted before the turn runs.
        //
        // # Why the question and not the answer
        //
        // [spokenLanguageFor] already reads the answer, but it reads the first clause of it and then
        // latches for the rest of the turn — and a first clause is often "Halo Michael," or "Hi
        // Michael,", which carries no function words and detects as nothing. It then falls back to
        // whatever the switch last said, and the whole answer is spoken in that voice. Ask in
        // English with the switch on ID and the reply is English words in an Indonesian voice, with
        // the number speller reading "Rp1,200,000" as "satu koma dua rupiah".
        //
        // A whole question is a far stronger signal than an answer's opening fragment, and it
        // arrives one step earlier — before the model is called, before a sample is synthesized. So
        // the language follows whoever is talking, which is what a bilingual customer means by
        // switching mid-conversation. The answer's own detection stays as the correction for a
        // question too short to read.
        //
        // This also points the recogniser at the right model for the *next* question, which is the
        // difference between hearing "How much money do I have" and transliterating it.
        //
        // The switch in the header keeps its job: it decides ambiguous input, and it is what a
        // customer reaches for when they want the reply in the other language than they asked in.
        val spoken = Language.detect(text)
        val listenFor = listenLanguageFor(spoken)
        _state.update {
            it.copy(
                language = spoken ?: it.language,
                listenFor = listenFor,
                transcript = history,
                draft = "",
                caption = null,
                phase = TurnPhase.THINKING,
                retryable = null,
                trace = TurnTrace(askedAtMs = System.currentTimeMillis(), listenedMs = listenedMs),
            )
        }

        // Adopted only when the finished transcript says the same thing as the one the speculation
        // was started on. Anything else is discarded unheard.
        val pending = speculation
        val adopted = pending?.takeIf { TurnRules.sameQuestion(it.question, text) }
        if (adopted == null) discardSpeculation() else speculation = null
        // Keyed on whether a speculation actually existed, not on how the question arrived. A
        // hold-to-talk question has a listening leg and no speculation, and reporting that as a
        // miss would invent a failure rate out of a path that never tried.
        speculated = when {
            adopted != null -> Speculated.ADOPTED
            pending != null -> Speculated.MISSED
            else -> Speculated.NONE
        }

        turn = viewModelScope.launch { runTurn(agent, history, adopted) }
    }

    /**
     * Starts answering [partialQuestion] before the customer has finished asking it.
     *
     * Called by the screen partway through the pause that ends a handsfree question — see
     * [speculation] for why, and [TurnRules.sameQuestion] for the rule that decides whether the
     * result may ever be used. Cheap to call repeatedly: a newer partial replaces an older
     * speculation, and the one it replaces is cancelled.
     *
     * Nothing about this is visible. No transcript entry, no phase change, no notice — a
     * speculation that is never adopted leaves no trace except a billed request.
     */
    fun speculate(partialQuestion: String) {
        val current = _state.value
        val agent = current.agent ?: return
        val text = partialQuestion.trim()
        if (text.isEmpty() || !current.acceptingInput) return
        if (speculation?.question == text) return

        discardSpeculation()
        val history = current.transcript + TranscriptEntry(Speaker.CUSTOMER, text)
        val fragments = Channel<String>(Channel.UNLIMITED)
        val chat = brains[current.brain]
        val job = viewModelScope.launch {
            runCatching { chat.reply(agent.id, history.asChatHistory()).collect(fragments::send) }
                .fold(
                    onSuccess = { fragments.close() },
                    // Closed with the failure rather than swallowed: if this speculation is adopted
                    // the turn must fail the way it would have failed on its own.
                    onFailure = { fragments.close(it) },
                )
        }
        speculation = Speculation(text, job, fragments)
    }

    /** Throws away the answer in flight, if any. */
    private fun discardSpeculation() {
        speculation?.let {
            it.job.cancel()
            it.fragments.close()
        }
        speculation = null
    }

    /**
     * Runs one turn end to end, in the sequence documented on the class: stream the answer, regroup
     * it into sentences, synthesize and speak each one as it completes, and wait for the avatar to
     * finish. Speech is best-effort — a failure past this point leaves the transcript intact and only
     * degrades the turn to captions — so failures here are swallowed into a notice, never thrown.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun runTurn(
        agent: Agent,
        history: List<TranscriptEntry>,
        adopted: Speculation? = null,
    ) {
        // A session near its cap would die mid-answer. Replacing it first costs a second of silence
        // before the turn; letting it expire costs the face in the middle of one.
        refreshIfExpiring(agent)

        // Latched on the first clause and held for the rest of the turn: switching language
        // mid-answer would change voice mid-sentence. See [spokenLanguageFor].
        var turnLanguage: Language? = null
        val answer = StringBuilder()

        // Clauses queue up here as their audio is sent, and [revealCaption] lets them out at the
        // speed they are spoken. Unlimited because the supply runs far ahead of the playback it is
        // describing — that gap is the entire reason this exists — and a full buffer would stall the
        // audio to keep a caption tidy, which is the wrong way round.
        // Told before any work is done, so a backend that times a turn by watching it — and that is
        // how the network breakdown is collected — has something for those spans to attach to.
        turns.begin()
        interrupted = false

        val clauses = Channel<SpokenCaption>(Channel.UNLIMITED)
        captionJob?.cancel()
        captionJob = viewModelScope.launch { revealCaption(clauses) }
        turnInFlight = true
        // Falls back to the demo API if the chosen brain has no key in this build, which is the same
        // rule the chooser uses — it just cannot be reached from the UI.
        // An adopted speculation is already generating; draining its channel replays what arrived
        // before the question finished and then follows the rest live, so everything downstream is
        // unchanged. Nothing else knows the difference.
        val chat = brains[_state.value.brain]
        val fragments = (adopted?.fragments?.receiveAsFlow() ?: chat.reply(agent.id, history.asChatHistory()))
            .onEach { fragment ->
                answer.append(fragment)
                mark { copy(firstTokenMs = firstTokenMs ?: elapsed()) }
                _state.update { it.copy(draft = answer.toString()) }
            }

        // A turn that failed — a network blip, a dropped socket — can leave this conversation with no
        // provider session at all. Without this the avatar never comes back: every later answer
        // arrives as text while the face sits there silent, which reads as the agent having given up.
        // One attempt, here, so recovery costs the customer nothing but the second they were already
        // waiting.
        val open = session ?: openSession(agent).let { if (it) session else null }
        var utterance: Utterance? = null
        val spoken = runCatching {
            if (open == null) {
                // No avatar, so nothing to be in step with: the caption follows the model directly.
                // Without this the stage would show nothing at all until the turn ended, because the
                // pacing below only runs when there is a voice pacing it.
                fragments.collect { _state.update { state -> state.copy(caption = answer.toString()) } }
            } else {
                controller.beginUtterance()
                utterance = open.speak(
                    frames = fragments
                        .sentences()
                        // Deliberately not logged. This is the customer's answer — their balances,
                        // their goals — and logcat is readable by anyone with the handset plugged in.
                        .onEach {
                            mark {
                                copy(
                                    sentences = sentences + 1,
                                    // The moment a clause is whole and synthesis can begin. Where
                                    // the model's work ends and the voice's starts.
                                    firstClauseMs = firstClauseMs ?: elapsed(),
                                )
                            }
                        }
                        .flatMapConcat { sentence ->
                            val language = turnLanguage
                                ?: spokenLanguageFor(answer.toString()).also { turnLanguage = it }
                            var bytes = 0L
                            synthesizer.speak(agent.avatar.voiceFor(language), sentence, language)
                                .onEach { bytes += it.size }
                                // Queued once its audio is whole, so the caption carries a duration
                                // rather than a guess. Order is [flatMapConcat]'s to keep.
                                .onCompletion { clauses.trySend(SpokenCaption(sentence, bytes)) }
                        },
                    onFirstFrame = { mark { copy(firstAudioMs = elapsed()) } },
                )
            }
        }
        mark { copy(answerCompleteMs = elapsed()) }

        val text = answer.toString().trim()
        if (text.isNotEmpty()) {
            _state.update {
                it.copy(transcript = it.transcript + TranscriptEntry(Speaker.AGENT, text), draft = null)
            }
        } else {
            _state.update { it.copy(draft = null) }
        }

        spoken.onFailure { failure ->
            // Every decision here is [TurnRules.ending]'s, so each one is a test rather than a
            // comment: what becomes of the caption, whether to offer the question again, and whether
            // the session is spent.
            val ending = TurnRules.ending(
                answered = text.isNotEmpty(),
                spoke = false,
                interrupted = false,
                hadSession = open != null,
            )
            // SHOW_ALL, so the pacing is cut short rather than drained: the rest of this answer is
            // never going to be spoken, and pacing it would describe audio that does not exist.
            clauses.close()
            captionJob?.cancel()
            turnInFlight = false
            controller.endUtterance()
            _state.update {
                it.copy(
                    caption = text.ifEmpty { null },
                    phase = TurnPhase.IDLE,
                    notice = notice(
                        when {
                            text.isNotEmpty() -> R.string.avatar_unavailable
                            // Naming the cause is the difference between a customer who taps retry
                            // and one who decides the app is broken. A dropped socket, a DNS
                            // failure, a timeout — all arrive as IOException, and all mean the same
                            // thing to the person holding the handset: it is the connection, not
                            // them, and trying again is worth it.
                            failure.isNetwork() -> R.string.answer_failed_network
                            else -> R.string.answer_failed
                        },
                    ),
                    retryable = if (ending.retryable) history.lastOrNull()?.text else it.retryable,
                )
            }
            if (ending.replaceSession) replaceSession(agent)
            Log.w(TAG, "turn failed", failure)
            recordTurn(
                TurnRules.outcome(
                    answered = text.isNotEmpty(),
                    spoke = false,
                    interrupted = interrupted,
                    network = failure.isNetwork(),
                ),
            )
            return
        }

        // Sending the last frame is not the end of speaking: the provider still has the audio to
        // render, and only it can say when the lips stopped.
        utterance?.awaitEnd()
        // And the provider is not the last word either.
        //
        // `agent.speak_ended` has been observed arriving while the avatar is plainly still talking
        // — 178ms after the lips started, for a three-sentence answer. Taken at face value the turn
        // ends there, the microphone opens on the next breath, and it hears the rest of the answer
        // and asks the agent about it. That is the conversation-with-itself, and no amount of
        // filtering the words afterwards fixes a microphone opened too early.
        //
        // The audio's own length is not a guess: it is the bytes this app sent, at a rate it
        // chose. So the event is treated as a lower bound and the audio as the other one, and the
        // turn ends at whichever is later.
        utterance?.let { waitOutRemainingAudio(it) }
        // Closed, not cancelled. The reveal is deliberately slower than the transfer, so it is still
        // a clause or so behind when the provider reports the lips have stopped — cancelling here
        // froze the caption mid-answer and left the rest of it never shown. Closing ends the loop
        // once it has drained, which takes at most the last clause's own length.
        clauses.close()
        turnInFlight = false
        controller.endUtterance()
        mark { copy(speakEndedMs = elapsed()) }
        _state.update { it.copy(phase = TurnPhase.IDLE) }
        // The brain is part of the measurement: an answer is only better than another if you know
        // which one gave it, and how long it took to give it.
        _state.value.trace?.let { Log.i(TAG, "turn ${_state.value.brain.name}: ${it.summary()}") }
        recordTurn(
            TurnRules.outcome(
                answered = text.isNotEmpty(),
                spoke = true,
                interrupted = interrupted,
                network = false,
            ),
        )
    }

    /**
     * Hands the finished turn to whoever is collecting them.
     *
     * Called on every path out of a turn, including the failures, because a backend that only hears
     * about the turns that worked reports a pipeline that never breaks. Nothing here can throw into
     * the turn: the sink's contract is that it does not, and the trace is gone by the next question
     * either way.
     */
    private fun recordTurn(outcome: TurnOutcome) {
        val state = _state.value
        val trace = state.trace ?: return
        val agent = state.agent
        turns.record(
            TurnEvent(
                trace = trace,
                // The anonymous label, not the enum's name. Naming the vendor here would put the
                // brand back on every chart and undo the reason the labels are anonymous in the
                // first place — see [Brain], where the whole point is that whoever judges these
                // answers judges the answer.
                brain = state.brain.label,
                agent = agent?.id.orEmpty(),
                language = state.language.tag,
                // Resolved the same way the turn resolved it, so the dimension names the voice that
                // actually spoke rather than the one the switch happens to point at now.
                voice = agent?.avatar?.voiceFor(state.language).orEmpty(),
                handsfree = state.handsfree,
                warmHit = warmed,
                speculated = speculated.name,
                // The answer as the customer received it, which is the length synthesis was paid
                // for. Only its size travels — never a character of it.
                answerChars = state.transcript.lastOrNull()
                    ?.takeIf { it.speaker == Speaker.AGENT }?.text?.length ?: 0,
                outcome = outcome,
            ),
        )
    }

    /**
     * Waits until the audio that was sent could actually have finished playing.
     *
     * Silent when the provider's report was honest, which is most of the time — the remaining
     * duration comes out at or below zero and this returns immediately. It only costs anything on
     * the turns where `speak_ended` was early, which are exactly the turns that were breaking
     * handsfree.
     *
     * Interruptions are exempt: barge-in clears what the provider had buffered, so the audio that
     * was sent is not going to be played and waiting for it would leave the customer looking at a
     * face that stopped talking a while ago.
     */
    private suspend fun waitOutRemainingAudio(utterance: Utterance) {
        if (interrupted) return
        val trace = _state.value.trace ?: return
        val startedAt = trace.speakStartedMs?.let { trace.askedAtMs + it } ?: return
        val remaining = startedAt + utterance.expectedDurationMs - System.currentTimeMillis()
        if (remaining <= 0) return
        Log.i(TAG, "audio has ${remaining}ms left to play; holding the turn open")
        delay(remaining)
    }

    /**
     * Lets clauses onto the screen at the speed they are being spoken.
     *
     * Waits for the provider to say the mouth has started — `agent.speak_started` — because until
     * then there is nothing to be in step with, and a caption that appears before the voice is the
     * same desync in the other direction. After that it is arithmetic: show a clause, wait out its
     * own audio, show the next.
     *
     * Drift is bounded by the provider's own buffering rather than accumulating, because each wait
     * is that clause's exact duration and the sum of the clauses is the length of the utterance.
     */
    private suspend fun revealCaption(clauses: ReceiveChannel<SpokenCaption>) {
        speaking = CompletableDeferred()
        revealCaptions(
            clauses = clauses,
            awaitStart = { speaking?.await() ?: Unit },
        ) { text -> _state.update { it.copy(caption = text) } }
    }

    /**
     * The language this answer is actually in, which is not always the one the switch says.
     *
     * # Why the switch is not enough
     *
     * The switch sets the recogniser, the voice and the number speller together, and customers do
     * not honour it — they ask in English with it on ID. The model answers in the language it was
     * asked in, and what reaches the synthesizer is then an English sentence in an Indonesian voice
     * run through a speller that reads "," as a decimal point: "Rp1,200,000" comes out as "satu koma
     * dua rupiah". Following the answer keeps all three in agreement without asking the customer to
     * announce anything.
     *
     * The conversation's own language is the fallback, because it is a better guess than a coin
     * toss when the text is too short to tell. A confident detection also moves the switch, so the
     * recogniser is listening for the right language by the time they reply.
     */
    private fun spokenLanguageFor(answerSoFar: String): Language {
        val detected = Language.detect(answerSoFar) ?: return _state.value.language
        if (detected != _state.value.language) {
            Log.i(TAG, "answer is in ${detected.label}; following it")
            _state.update { it.copy(language = detected) }
        }
        return detected
    }

    /**
     * Runs the failed turn again.
     *
     * The question is already the last thing in the transcript, so this re-runs the turn over the
     * existing history rather than appending a duplicate — the customer asked once, and the screen
     * should not imply otherwise just because the network did not hold.
     */
    fun retry() {
        val current = _state.value
        val agent = current.agent ?: return
        if (current.retryable == null || !current.acceptingInput) return

        _state.update {
            it.copy(
                retryable = null,
                phase = TurnPhase.THINKING,
                trace = TurnTrace(askedAtMs = System.currentTimeMillis()),
            )
        }
        turn = viewModelScope.launch { runTurn(agent, current.transcript) }
    }

    /** Whether a turn was answered ahead of time, and if not, why not. */
    private enum class Speculated {
        /** Not a spoken question, so there was no pause to speculate during. */
        NONE,

        /** Speculated, and the finished transcript said the same thing. The fast path. */
        ADOPTED,

        /** Speculated and thrown away: the customer said something other than the guess. */
        MISSED,
    }

    /** Cuts the current answer short. The turn ends where the customer stopped it. */
    fun interrupt() {
        if (!_state.value.interruptible) return
        interrupted = true
        // The caption stops where the voice stopped. Letting it run on would show the customer the
        // words they just cut off, which is the opposite of what pressing stop meant.
        captionJob?.cancel()
        session?.interrupt()
    }

    /**
     * Hands the conversation over to the keyboard.
     *
     * # Why suppressing playout was not enough
     *
     * Opening the thread used to only mute the avatar locally, which silenced the answer without
     * ending it: the provider went on rendering, the turn went on waiting for it, and [phase] stayed
     * [TurnPhase.SPEAKING] for the rest of the answer's length. Everything gated on
     * [CompanionUiState.acceptingInput] stayed shut with it — including the composer's own text
     * field, so the customer met a thread they could read and not type into, for no reason they
     * could see. Ending the utterance is what "I would rather read this" actually means.
     *
     * Handsfree goes with it. The microphone is already closed on this surface because the stage is
     * gone, but the flag survived the switch: the stage's button still read "listening" on the way
     * back, and the mic re-armed itself the moment the face returned. A mode that cannot act is a
     * mode that should not claim to be on — coming back to the stage re-arms it with the same
     * deliberate double tap that started it.
     */
    fun enterTextMode() {
        interrupt()
        _state.update { it.copy(handsfree = false) }
    }

    /** Turns a microphone failure the screen reported into copy the customer actually sees. */
    fun onSpeechProblem(problem: SpeechProblem) {
        val message = when (problem) {
            SpeechProblem.PERMISSION_JUST_GRANTED -> R.string.mic_permission_needed
            SpeechProblem.PERMISSION_DENIED -> R.string.mic_denied
            SpeechProblem.UNAVAILABLE -> R.string.mic_unavailable
            SpeechProblem.NO_NETWORK -> R.string.mic_no_network
            SpeechProblem.NO_MATCH, SpeechProblem.FAILED -> R.string.mic_no_match
        }
        _state.update { it.copy(notice = notice(message)) }
    }

    /**
     * Points the conversation at the language the app is in.
     *
     * # Why this is set rather than toggled
     *
     * The switch used to live here, which made the conversation the authority on a decision the
     * whole app answers to — the picker has no conversation to ask. It is hoisted now: the app owns
     * the choice, and this is how the conversation hears about it.
     *
     * The traffic is one-way on purpose. [spokenLanguageFor] may still move the conversation's
     * language when an answer comes back in the other one, because the voice and the number speller
     * have to follow the words actually being spoken. That correction stays down here; one question
     * asked in English is not a request to rewrite every button in the app.
     *
     * Ignored mid-turn, as the toggle was: the voice is resolved when a turn starts, so changing it
     * while the avatar is speaking would only confuse the next question.
     */
    fun setLanguage(language: Language) {
        if (!_state.value.acceptingInput) return
        // An explicit choice moves the ear at once and with no streak to serve out. The customer
        // said which language they are about to speak; there is nothing left to infer.
        offLanguageStreak = 0
        _state.update { it.copy(language = language, listenFor = language) }
    }

    /**
     * How many questions in a row have come in a language the recogniser is not listening for.
     *
     * Not in [CompanionUiState] because nothing draws it — it is the evidence behind [listenFor],
     * not a thing the screen has any business knowing.
     */
    private var offLanguageStreak = 0

    /** Applies [ListenLanguage] to this conversation, carrying the streak across questions. */
    private fun listenLanguageFor(spoken: Language?): Language {
        val next = ListenLanguage.next(_state.value.listenFor, spoken, offLanguageStreak)
        offLanguageStreak = next.streak
        return next.language
    }

    /** Silences or restores the avatar's voice on this device, independent of language or turn state. */
    fun toggleMute() = controller.setAudioMuted(!_state.value.muted)

    /**
     * Turns continuous listening on or off. See [CompanionUiState.handsfree] for why it is a mode.
     *
     * The screen owns the microphone, so this only flips the flag and lets the stage react — which
     * keeps the recogniser's lifetime tied to the composition that can actually stop it.
     */
    fun toggleHandsfree() = _state.update { it.copy(handsfree = !it.handsfree) }

    /**
     * Leaves handsfree because the room has gone quiet.
     *
     * Re-arming forever on an empty room holds the microphone open for as long as the screen is up,
     * which is both a battery cost and, more to the point, not something a customer expects an app
     * to do unasked. The stage times how long it has been since a question was last heard and calls
     * this once that stretch passes a fixed budget; the notice explains why the microphone stopped,
     * so it does not read as a failure.
     */
    /**
     * Explains the microphone after a tap that did nothing.
     *
     * The button has two gestures and neither is a tap: hold it to talk, double tap it for
     * handsfree. Someone who taps once has done neither, and silence is the worst possible answer to
     * "did I press it right?".
     */
    fun micNeedsAHold() = _state.update { it.copy(notice = notice(R.string.companion_mic_tap_hint)) }

    fun handsfreeTimedOut() =
        _state.update { it.copy(handsfree = false, notice = notice(R.string.handsfree_timed_out)) }

    /** Shows or hides the timing trace, toggled by the long-press on the agent name in the top bar. */
    fun toggleTrace() = _state.update { it.copy(showTrace = !it.showTrace) }

    /** Clears the current [Notice] once the screen has displayed it, so it is not shown a second time. */
    fun onNoticeShown() = _state.update { it.copy(notice = null) }

    /** Called when the customer leaves the conversation, so the billed session stops with the screen. */
    fun close() {
        turn?.cancel()
        discardSpeculation()
        closeSession()
        controller.detach()
        _state.value = CompanionUiState()
    }

    /**
     * Mirrors [close] for the case where the screen is torn down without calling it directly.
     *
     * It skips cancelling [turn] and resetting [state]: `viewModelScope` is already being cancelled by
     * the framework at this point, and nothing is left observing [state] once this runs.
     */
    override fun onCleared() {
        closeSession()
        controller.detach()
    }

    /**
     * Opens a provider session and joins the room it publishes into.
     *
     * Returns false and leaves the screen in captions-only if the provider refuses — which is not a
     * reason to end the conversation, only a reason to have no face during it.
     */
    private suspend fun openSession(agent: Agent): Boolean {
        val startedAt = System.currentTimeMillis()
        val opening = sessions.create()
        session = opening
        sessionEvents?.cancel()
        sessionEvents = viewModelScope.launch {
            opening.events.collect { event ->
                when (event) {
                    is LiveAvatarEvent.SpeakStarted -> {
                        if (turnInFlight) {
                            mark { copy(speakStartedMs = elapsed()) }
                            speaking?.complete(Unit)
                            _state.update { it.copy(phase = TurnPhase.SPEAKING) }
                        }
                    }
                    is LiveAvatarEvent.Starved -> {
                        // Counted rather than just logged: this is the one number that says whether
                        // the audio supply is keeping up with the mouth it is driving.
                        mark { copy(starved = starved + 1) }
                        Log.w(TAG, "renderer starved; the line played broken")
                    }
                    is LiveAvatarEvent.Failed -> Log.w(TAG, "provider: ${event.reason}")
                    is LiveAvatarEvent.Dead -> {
                        // Let go of it immediately rather than finding out on the next question.
                        //
                        // A session whose socket has gone still looks usable — the field is not null
                        // — so the next turn handed its audio to a corpse, failed, and only then
                        // replaced it. The customer paid for that discovery with a turn and an error
                        // message about something they did not do. Dropping it here means the next
                        // question opens a fresh session on its way in, which is the behaviour the
                        // retry already proved works.
                        if (session === opening) {
                            session = null
                            applicationScope.launch { opening.close() }
                            _state.update { it.copy(avatarLive = false) }
                        }
                    }
                    else -> Unit
                }
            }
        }

        return runCatching { opening.open(agent.avatar.avatarId) }.fold(
            onSuccess = { stream ->
                Log.i(TAG, "stage: session open in ${System.currentTimeMillis() - startedAt}ms")
                attachedAtMs = System.currentTimeMillis()
                controller.attach(stream)
                true
            },
            onFailure = {
                // A billed session may be half-open; drop it rather than leave it running.
                session = null
                applicationScope.launch { opening.close() }
                _state.update { it.copy(avatarLive = false, notice = notice(R.string.avatar_unavailable)) }
                Log.w(TAG, "could not open an avatar session", it)
                false
            },
        )
    }

    /**
     * Watches for the provider's session cap while the customer is reading rather than asking.
     *
     * LiveAvatar ends a session at `max_session_duration` — five minutes on this account — and a
     * conversation that pauses for a think would otherwise come back to a dead face. Replacing it
     * during the pause is invisible; replacing it after it dies is not.
     */
    private fun startRefreshWatchdog(agent: Agent) {
        refresh?.cancel()
        refresh = viewModelScope.launch {
            while (true) {
                delay(REFRESH_CHECK_MS)
                if (_state.value.phase == TurnPhase.IDLE) refreshIfExpiring(agent)
            }
        }
    }

    private suspend fun refreshIfExpiring(agent: Agent) {
        val open = session ?: return
        if (System.currentTimeMillis() < open.expiresAtMs - REFRESH_MARGIN_MS) return
        replaceSession(agent)
    }

    private suspend fun replaceSession(agent: Agent) {
        closeSession()
        _state.update { it.copy(avatarLive = false) }
        openSession(agent)
    }

    private fun closeSession() {
        sessionEvents?.cancel()
        sessionEvents = null
        val open = session ?: return
        session = null
        // Deliberately not `viewModelScope`: by the time `onCleared` runs that scope is cancelled,
        // and the provider would keep billing the session until its own idle timeout fired.
        applicationScope.launch { open.close() }
    }

    /** Updates the live trace, if a turn is being timed. */
    private fun mark(update: TurnTrace.() -> TurnTrace) =
        _state.update { it.copy(trace = it.trace?.update()) }

    private fun TurnTrace.elapsed(): Long = System.currentTimeMillis() - askedAtMs

    private fun notice(@StringRes message: Int) = Notice(id = ++noticeSeq, message = message)

    /**
     * Whether a failure is the network rather than the app.
     *
     * Every transport fault OkHttp raises — a dropped socket, a DNS miss, a read timeout — arrives as
     * an [IOException], and coroutine machinery may wrap it, so the cause chain is walked rather than
     * the top frame inspected. Anything else is a bug or a bad response, and saying "check your
     * connection" about one of those sends the customer to fix something that is not broken.
     */
    private fun Throwable.isNetwork(): Boolean {
        var cause: Throwable? = this
        // Bounded rather than walked to the end. Java forbids an exception causing itself, but
        // nothing stops two from causing each other, and an unbounded walk over that pair never
        // returns. No real chain is anywhere near this deep.
        repeat(CAUSE_DEPTH) {
            if (cause == null) return false
            if (cause is IOException) return true
            cause = cause?.cause
        }
        return false
    }

    private companion object {
        const val TAG = "chatty.turn"

        /** How often an idle conversation checks whether its session is about to be reaped. */
        const val REFRESH_CHECK_MS = 15_000L


        /** How far to follow a failure's causes before giving up. Deeper than any real chain. */
        const val CAUSE_DEPTH = 16

        /** Replace a session with this much of its life left, so no turn ever starts on a dying one. */
        const val REFRESH_MARGIN_MS = 45_000L
    }
}
