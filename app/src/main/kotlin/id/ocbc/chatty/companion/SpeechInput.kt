package id.ocbc.chatty.companion

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import id.ocbc.chatty.core.ai.Language
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Why the microphone produced no question. Each maps to one line of copy, and no more. */
enum class SpeechProblem {
    /** The permission dialog has only just been answered; the press that opened it cannot also record. */
    PERMISSION_JUST_GRANTED,

    /** The handset has no recognition service at all. Typing is the only way in. */
    UNAVAILABLE,

    /**
     * The customer said no to the microphone.
     *
     * Distinct from [FAILED] because it is a decision, not a fault: telling someone who declined
     * that their speech "was not caught" is both wrong and slightly insulting, and the way out is
     * Settings rather than saying it again.
     */
    PERMISSION_DENIED,

    /** The recogniser heard nothing it could transcribe. */
    NO_MATCH,

    /**
     * Recognition needs the network and there isn't one.
     *
     * Distinct from [NO_MATCH] because the customer did nothing wrong: transcription happens through
     * a service that has to be reachable, and telling someone their speech "was not caught" when the
     * connection is down blames them for the wrong thing and invites them to repeat themselves into
     * a microphone that still cannot work.
     */
    NO_NETWORK,

    /** The recognition service itself errored, or the permission request was denied. */
    FAILED,
}

/** Hold-to-talk, as the screen drives it. */
interface SpeechInput {
    /** True between [start] and the result. Drives the listening dot, nothing else. */
    val listening: State<Boolean>

    /**
     * What the recogniser has heard so far, updated while the button is held.
     *
     * Shown in the composer as the customer speaks. Partial text is the difference between a
     * microphone that looks alive and one that looks broken — a held button with no feedback reads
     * as a hang, especially on the first press of a session. Empty between utterances.
     */
    val partial: State<String>

    /** Whether this device can transcribe at all. False disables the microphone rather than hiding it. */
    val available: Boolean

    /**
     * Begins listening.
     *
     * May not actually start: on a device with no recognizer this reports [SpeechProblem.UNAVAILABLE],
     * and on the first call ever it requests the microphone permission instead of listening — the
     * press that triggers the system dialog cannot also record over it, so that case is reported as
     * [SpeechProblem.PERMISSION_JUST_GRANTED] rather than silently doing nothing.
     *
     * [endpointed] decides who says when the question finished. False is hold-to-talk: the customer's
     * finger is the end of the utterance, and the recogniser is asked to wait rather than guess. True
     * is handsfree: nobody is touching the screen, so the recogniser is given a shorter silence hint —
     * but a hint is all it owes you, and on the handset this was built against it was ignored outright,
     * so the caller ends the turn itself rather than trusting the recogniser to, and
     * [SpeechProblem.NO_MATCH] becomes an ordinary event — a quiet room — rather than a fault.
     */
    fun start(endpointed: Boolean = false)

    /** Ends the utterance and asks for the transcription of what has been heard so far. */
    fun stop()

    /**
     * Drops the utterance without transcribing it.
     *
     * Leaving the stage mid-listen must not post the half-sentence that was in the air as a question.
     * Unlike [stop], nothing is delivered to `onResult`.
     */
    fun cancel()
}

/**
 * On-device speech recognition, wired to the composition that uses it.
 *
 * The recording never leaves the handset: Android transcribes locally (or through the user's own
 * chosen recognition service) and this app sends only the resulting text. That is the whole reason
 * the microphone is here rather than in the LiveAvatar session — LITE mode never receives audio from
 * the customer, and FULL mode, which would, is refused in [id.ocbc.chatty.core.avatar.LiveAvatarSession].
 *
 * The permission is requested on the first press. That press cannot also record — the dialog is
 * still up when it returns — so it reports [SpeechProblem.PERMISSION_JUST_GRANTED] instead of
 * silently doing nothing, which is what makes the button look broken.
 *
 * ```
 * val speech = rememberSpeechInput(onResult = viewModel::ask, onProblem = viewModel::onSpeechProblem)
 * // onResult also reports how long the recogniser took to decide, which is the one leg of a turn
 * // that happens before the turn starts. See RecognitionCallbacks.onEndOfSpeech.
 * MicButton(
 *     enabled = speech.available,
 *     onPress = { speech.start() },
 *     // A press too short to be speech is an accident, not a question. See MIC_MIN_HOLD_MS.
 *     onRelease = { if (heldLongEnough) speech.stop() else speech.cancel() },
 * )
 * ```
 */
@Composable
fun rememberSpeechInput(
    onResult: (question: String, listenedMs: Long?) -> Unit,
    onProblem: (SpeechProblem) -> Unit,
    languageTag: String = Language.INDONESIAN.tag,
): SpeechInput {
    val context = LocalContext.current
    val listening = remember { mutableStateOf(false) }
    val partial = remember { mutableStateOf("") }
    val available = remember(context) { SpeechRecognizer.isRecognitionAvailable(context) }

    val granted = remember { mutableStateOf(context.hasRecordAudioPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted.value = allowed
        onProblem(
            if (allowed) SpeechProblem.PERMISSION_JUST_GRANTED else SpeechProblem.PERMISSION_DENIED,
        )
    }

    val recognizer = remember(context, available) {
        if (available) SpeechRecognizer.createSpeechRecognizer(context) else null
    }

    // Raised by [SpeechInput.cancel], lowered by the first callback that follows it. A cancelled
    // recogniser is not silent: it still reports the utterance it was in the middle of, usually as
    // ERROR_NO_MATCH, and without this the customer gets "not caught" for speech they deliberately
    // threw away — which on the stage happens every time they mis-tap the microphone.
    val discarding = remember { mutableStateOf(false) }

    // All hoisted for the same reason [discarding] is: the listener below is rebuilt on every
    // recomposition, and partial results cause recompositions while the customer is still talking,
    // so anything the listener keeps for itself is wiped during the utterance it is describing.
    //
    // [lastWordsAt] is the fallback for measuring the wait. The obvious signal, onEndOfSpeech,
    // turned out to fire about ten milliseconds before the transcript arrives on this handset —
    // it reports that recognition finished, not that the customer stopped — so the last time new
    // words appeared is the honest stand-in.
    //
    // [peakRms] and [heardSound] exist to tell the two handsfree failures apart: audio arriving and
    // not being recognised, versus no audio arriving at all.
    val lastWordsAt = remember { mutableStateOf<Long?>(null) }
    val peakRms = remember { mutableStateOf(0) }
    val heardSound = remember { mutableStateOf(false) }

    // Hoisted for the same reason [discarding] is, and it was a bug before it was hoisted: the
    // listener below is rebuilt on every recomposition, and partial results cause recompositions
    // while the customer is still talking. State kept inside the listener is therefore wiped
    // between the end of speech and the transcript arriving — which is precisely the interval this
    // measures, so it read null on every single question.
    val stoppedTalkingAt = remember { mutableStateOf<Long?>(null) }

    // The listener closes over the callers' lambdas, which are recreated on every recomposition.
    // Re-attaching it each time is cheap and keeps a stale `onResult` from answering a live turn.
    DisposableEffect(recognizer, onResult, onProblem) {
        recognizer?.setRecognitionListener(
            RecognitionCallbacks(
                listening = listening,
                partial = partial,
                discarding = discarding,
                stoppedTalkingAt = stoppedTalkingAt,
                lastWordsAt = lastWordsAt,
                peakRms = peakRms,
                heardSound = heardSound,
                onResult = onResult,
                onProblem = onProblem,
            ),
        )
        onDispose { }
    }

    DisposableEffect(recognizer) {
        onDispose {
            // A recogniser outliving its screen holds a bound service and the microphone with it.
            recognizer?.cancel()
            recognizer?.destroy()
        }
    }

    return remember(recognizer, available, languageTag, granted.value) {
        RecognizerSpeechInput(
            recognizer = recognizer,
            available = available,
            languageTag = languageTag,
            listening = listening,
            partial = partial,
            discarding = discarding,
            isGranted = { granted.value },
            requestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            onProblem = onProblem,
        )
    }
}

/**
 * [SpeechInput] backed by a real (or absent) [SpeechRecognizer].
 *
 * [start] has three outcomes, checked in order: no recognizer on the device reports
 * [SpeechProblem.UNAVAILABLE] and goes no further; permission not yet granted requests it and returns,
 * because the press that opens that dialog cannot also record; only then does it actually start
 * listening.
 */
private class RecognizerSpeechInput(
    private val recognizer: SpeechRecognizer?,
    override val available: Boolean,
    private val languageTag: String,
    override val listening: MutableState<Boolean>,
    override val partial: MutableState<String>,
    private val discarding: MutableState<Boolean>,
    private val isGranted: () -> Boolean,
    private val requestPermission: () -> Unit,
    private val onProblem: (SpeechProblem) -> Unit,
) : SpeechInput {

    override fun start(endpointed: Boolean) {
        if (recognizer == null) {
            onProblem(SpeechProblem.UNAVAILABLE)
            return
        }
        if (!isGranted()) {
            requestPermission()
            return
        }
        // Starting a recogniser that is already listening throws away the utterance in progress on
        // some implementations and is ignored on others. Neither is what the caller meant.
        if (listening.value) return
        listening.value = true
        discarding.value = false
        partial.value = ""
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)

                // Let the recogniser change language on its own when it hears one.
                //
                // The switch in the top bar is a statement about the conversation, and customers do
                // not honour it — they ask in English with it on ID. Pinned to id-ID, Google's
                // recogniser does not fail on an English question, it *transliterates* it: "How much
                // money do I have" came back as "Oh macam mana". Nothing downstream can recover from
                // that, because the words are gone by then.
                //
                // Android 13 added this for exactly the bilingual case; on 12 and below the switch
                // remains the only way, which is why it stays on screen. Restricting the candidates
                // needs 14, and where it is available it is worth having: the two languages this app
                // speaks are the two it should be guessing between, not every language installed.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    putExtra(
                        RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                        RecognizerIntent.LANGUAGE_SWITCH_BALANCED,
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                        arrayListOf(Language.INDONESIAN.tag, Language.ENGLISH.tag),
                    )
                }
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Drives the live text under the microphone while listening.
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)

                // Handsfree has no finger to mark the end of the question, so the pause after
                // speaking has to. These are hints — an engine is free to ignore them — but where
                // they are honoured they are the difference between a companion that waits for you
                // to finish a thought and one that interrupts you for pausing.
                //
                // Hold-to-talk sets the same keys in the opposite direction: the customer is holding
                // the button, and the recogniser ending the turn under them because they paused to
                // think is the single most irritating way this can fail.
                val silence = if (endpointed) ENDPOINT_SILENCE_MS else HELD_SILENCE_MS
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, silence)
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    silence,
                )
                // How long to wait for the customer to say anything at all. Short in handsfree: an
                // empty room should cycle back to a fresh listen rather than hold the microphone.
                putExtra(
                    RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                    if (endpointed) ENDPOINT_MINIMUM_MS else HELD_MINIMUM_MS,
                )
            },
        )
    }

    override fun stop() {
        if (!listening.value) return
        recognizer?.stopListening()
    }

    override fun cancel() {
        if (!listening.value) return
        listening.value = false
        partial.value = ""
        discarding.value = true
        recognizer?.cancel()
    }
}

private const val SPEECH_TAG = "chatty.speech"

/** The recogniser's own name for a failure, because the integer alone tells a reader nothing. */
private fun Int.errorName(): String = when (this) {
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "NETWORK_TIMEOUT"
    SpeechRecognizer.ERROR_NETWORK -> "NETWORK"
    SpeechRecognizer.ERROR_AUDIO -> "AUDIO"
    SpeechRecognizer.ERROR_SERVER -> "SERVER"
    SpeechRecognizer.ERROR_CLIENT -> "CLIENT"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "SPEECH_TIMEOUT"
    SpeechRecognizer.ERROR_NO_MATCH -> "NO_MATCH"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "RECOGNIZER_BUSY"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "INSUFFICIENT_PERMISSIONS"
    SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "TOO_MANY_REQUESTS"
    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "SERVER_DISCONNECTED"
    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "LANGUAGE_NOT_SUPPORTED"
    SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "LANGUAGE_UNAVAILABLE"
    SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "CANNOT_CHECK_SUPPORT"
    else -> "UNKNOWN($this)"
}

/** The pause that ends a handsfree question. Long enough to think mid-sentence, short enough to feel answered. */
private const val ENDPOINT_SILENCE_MS = 1_500L

/** The shortest handsfree utterance, so a cough does not become a question. */
private const val ENDPOINT_MINIMUM_MS = 700L

/**
 * Hold-to-talk asks the recogniser to sit still: the button is the endpoint, and these are set high
 * so a thinking pause does not submit half a question.
 */
private const val HELD_SILENCE_MS = 6_000L
private const val HELD_MINIMUM_MS = 1_500L

/**
 * Translates [android.speech.SpeechRecognizer]'s callback-per-event API into the two observable
 * states [SpeechInput] exposes. A timeout and an actual no-match error are both reported as
 * [SpeechProblem.NO_MATCH] because, from the customer's seat, "gave up" and "heard silence" want the
 * same one line of copy; every other recognizer error collapses to [SpeechProblem.FAILED].
 */
private class RecognitionCallbacks(
    private val listening: MutableState<Boolean>,
    private val partial: MutableState<String>,
    private val discarding: MutableState<Boolean>,
    private val stoppedTalkingAt: MutableState<Long?>,
    private val lastWordsAt: MutableState<Long?>,
    private val peakRms: MutableState<Int>,
    private val heardSound: MutableState<Boolean>,
    private val onResult: (question: String, listenedMs: Long?) -> Unit,
    private val onProblem: (SpeechProblem) -> Unit,
) : RecognitionListener {

    /** True once for the one callback that a [SpeechInput.cancel] is still going to produce. */
    private fun cancelled(): Boolean = discarding.value.also { discarding.value = false }

    override fun onResults(results: Bundle?) {
        listening.value = false
        partial.value = ""
        // The last time new words arrived, not the end-of-speech callback: on this handset that
        // callback fires ~10ms before this one, so it measures recognition finishing rather than
        // the customer finishing.
        val listenedMs = lastWordsAt.value?.let { System.currentTimeMillis() - it }
        Log.i(SPEECH_TAG, "results after ${listenedMs}ms of quiet, peak rms ${peakRms.value}")
        stoppedTalkingAt.value = null
        lastWordsAt.value = null
        if (cancelled()) return
        val text = results
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.trim()
            .orEmpty()
        if (text.isEmpty()) onProblem(SpeechProblem.NO_MATCH) else onResult(text, listenedMs)
    }

    override fun onError(error: Int) {
        listening.value = false
        partial.value = ""
        // Logged even when it is not worth telling the customer about. A quiet room reports
        // NO_MATCH and deliberately says nothing on screen, which is right — but it left the log
        // unable to tell an empty room from a recogniser that refused, and handsfree failing in
        // the field looked exactly like handsfree working in silence.
        Log.i(
            SPEECH_TAG,
            "recogniser error ${error.errorName()}, " +
                "${if (heardSound.value) "speech was detected" else "no speech detected"}, " +
                "peak rms ${peakRms.value}",
        )
        stoppedTalkingAt.value = null
        lastWordsAt.value = null
        if (cancelled()) return
        onProblem(
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                -> SpeechProblem.NO_MATCH
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                -> SpeechProblem.NO_NETWORK
                else -> SpeechProblem.FAILED
            },
        )
    }

    override fun onReadyForSpeech(params: Bundle?) {
        heardSound.value = false
        peakRms.value = 0
        Log.i(SPEECH_TAG, "microphone open")
    }

    override fun onBeginningOfSpeech() {
        heardSound.value = true
    }

    /**
     * The loudest thing heard, kept so a failure can say whether audio arrived at all.
     *
     * This is what separates the two ways handsfree fails. A peak near zero means nothing reached
     * the recogniser and the problem is the microphone or whoever else is holding it; a healthy
     * peak with no transcript means audio arrived and recognition is what failed. The callback
     * fires many times a second, so only the peak is kept.
     */
    override fun onRmsChanged(rmsdB: Float) {
        val level = rmsdB.toInt()
        if (level > peakRms.value) peakRms.value = level
    }
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    /**
     * The recogniser has decided the customer stopped talking.
     *
     * The start of the only leg of a turn that happens before the turn does: everything between
     * here and [onResults] is the recogniser making up its mind, and in handsfree that includes the
     * fixed silence it waits out before it will call a sentence finished. It was invisible for the
     * life of this app — the trace begins when the *question arrives* — which made the wait the
     * customer feels most sharply the one nobody could put a number on.
     *
     * Stored in hoisted state rather than a field here, because this object does not survive the
     * interval it is timing. See [stoppedTalkingAt].
     */
    override fun onEndOfSpeech() {
        stoppedTalkingAt.value = System.currentTimeMillis()
    }
    override fun onPartialResults(partialResults: Bundle?) {
        if (discarding.value) return
        partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            ?.let {
                // Only when the words actually changed. A recogniser that repeats its current best
                // guess on a timer would otherwise keep resetting the clock and report no wait at
                // all, which is the opposite of what this measures.
                if (it != partial.value) lastWordsAt.value = System.currentTimeMillis()
                partial.value = it
            }
    }
    override fun onEvent(eventType: Int, params: Bundle?) = Unit
}

/**
 * Whether this handset currently has a usable network.
 *
 * Used to tell two failures apart that the recogniser itself reports identically. Offline, Google's
 * service does not return `ERROR_NETWORK` — it returns `ERROR_NO_MATCH`, the same code it uses for a
 * silent room — so the copy blamed the customer's speech for a connection problem and invited them
 * to say it again into a microphone that could not work either way. The recogniser cannot tell us,
 * but the platform can.
 */
internal fun Context.hasNetwork(): Boolean {
    val manager = getSystemService(ConnectivityManager::class.java) ?: return true
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private fun Context.hasRecordAudioPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
