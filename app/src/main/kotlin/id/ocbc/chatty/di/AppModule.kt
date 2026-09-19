package id.ocbc.chatty.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import id.ocbc.chatty.BuildConfig
import id.ocbc.chatty.core.ai.AgentProfiles
import id.ocbc.chatty.core.ai.ChatClient
import id.ocbc.chatty.core.ai.DefaultJson
import id.ocbc.chatty.core.ai.ElevenLabsSynthesizer
import id.ocbc.chatty.core.ai.HttpChatClient
import id.ocbc.chatty.core.ai.SpeechSynthesizer
import id.ocbc.chatty.core.avatar.AvatarController
import id.ocbc.chatty.core.avatar.LiveAvatarSession
import id.ocbc.chatty.core.avatar.LiveKitAvatarController
import id.ocbc.chatty.core.ai.telemetry.TelemetryPolicy
import id.ocbc.chatty.core.ai.telemetry.TurnSink
import id.ocbc.chatty.telemetry.LogTurnSink
import id.ocbc.chatty.telemetry.SentryTelemetry
import id.ocbc.chatty.telemetry.SentryTurnSink
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import io.sentry.okhttp.SentryOkHttpEventListener
import okhttp3.OkHttpClient
import id.ocbc.chatty.core.ai.AnthropicChatClient
import id.ocbc.chatty.core.ai.Brain
import id.ocbc.chatty.core.ai.Brains
import id.ocbc.chatty.core.ai.CustomerRecords
import id.ocbc.chatty.core.ai.HttpCustomerRecords
import id.ocbc.chatty.core.ai.OpenAiChatClient

/** Builds one [LiveAvatarSession]. A session is bound to one avatar id, so it cannot be a singleton. */
fun interface AvatarSessionFactory {
    fun create(): LiveAvatarSession
}

/**
 * A scope that outlives any one screen.
 *
 * Closing a LiveAvatar session is the work that must not be cancelled by the thing that triggers it:
 * a ViewModel's own scope is already cancelled by the time `onCleared` runs, and a session left open
 * keeps billing until the provider's idle timeout fires minutes later.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * One client for all three vendors.
     *
     * The read timeout is generous because the longest read in the app is a text-to-speech body that
     * arrives *as it renders* — a short read timeout would abort a healthy synthesis mid-sentence.
     * The ping interval keeps the LiveAvatar command socket alive through a NAT that would otherwise
     * drop an idle connection between questions.
     */
    /**
     * What this build is allowed to send. One value, edited in one place — see [TelemetryPolicy].
     *
     * Provided rather than read as a global so a test, or a build variant that should send less,
     * can replace it without touching the code that records anything.
     */
    @Provides
    @Singleton
    fun telemetryPolicy(): TelemetryPolicy = TelemetryPolicy.Default

    /**
     * Which adapter receives completed turns.
     *
     * The DSN decides, and its absence is an ordinary configuration rather than a failure: no DSN
     * means turns go to logcat and no further, which is what a developer's build does and what any
     * build does until someone configures a backend. Swapping vendors is another `TurnSink`
     * implementation and this one line.
     */
    @Provides
    @Singleton
    fun turnSink(): TurnSink =
        if (BuildConfig.SENTRY_DSN.isNotBlank()) SentryTurnSink() else LogTurnSink()

    @Provides
    @Singleton
    fun okHttp(policy: TelemetryPolicy): OkHttpClient = OkHttpClient.Builder()
        // The whole network breakdown — DNS, TLS, connect, request, response — for all four
        // vendors, onto whatever transaction is live. It is the reason this app can tell a slow
        // vendor from a slow handshake without timing a single socket itself. Harmless when no
        // backend is configured: with Sentry uninitialised these produce nothing.
        //
        // The interceptor carries the policy because what it may capture on a failure is part of
        // it — see [SentryTelemetry.okHttpInterceptor].
        .addInterceptor(SentryTelemetry.okHttpInterceptor(policy))
        .eventListener(SentryOkHttpEventListener())
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun json(): Json = DefaultJson

    @Provides
    @Singleton
    fun chatClient(http: OkHttpClient, json: Json): ChatClient = HttpChatClient(
        calls = http,
        baseUrl = BuildConfig.CHATTY_BASE_URL,
        apiKey = BuildConfig.CHATTY_API_KEY,
        json = json,
    )

    @Provides
    @Singleton
    fun customerRecords(http: OkHttpClient): CustomerRecords = HttpCustomerRecords(
        calls = http,
        baseUrl = BuildConfig.CHATTY_BASE_URL,
        apiKey = BuildConfig.CHATTY_API_KEY,
    )

    /**
     * Every brain the build has a key for, keyed by the choice that selects it.
     *
     * A missing key leaves that entry out rather than failing: the chooser is built from this map,
     * so a build without an Anthropic key simply does not offer it, and the demo still runs on the
     * one key it has always needed. [Brain.KAMARTAJ] is always present — it is the app's own API.
     */
    @Provides
    @Singleton
    fun brains(
        http: OkHttpClient,
        json: Json,
        roster: ChatClient,
        records: CustomerRecords,
    ): Brains = Brains(
        buildMap {
        put(Brain.KAMARTAJ, roster)
        if (BuildConfig.ANTHROPIC_API_KEY.isNotEmpty()) {
            put(
                Brain.ANTHROPIC,
                AnthropicChatClient(
                    calls = http,
                    apiKey = BuildConfig.ANTHROPIC_API_KEY,
                    json = json,
                    roster = roster,
                    records = records,
                ),
            )
        }
        if (BuildConfig.OPENAI_API_KEY.isNotEmpty()) {
            put(
                Brain.OPENAI,
                OpenAiChatClient(
                    calls = http,
                    apiKey = BuildConfig.OPENAI_API_KEY,
                    json = json,
                    roster = roster,
                    records = records,
                ),
            )
        }
        },
    )

    @Provides
    @Singleton
    fun synthesizer(http: OkHttpClient, json: Json): SpeechSynthesizer = ElevenLabsSynthesizer(
        calls = http,
        apiKey = BuildConfig.ELEVENLABS_API_KEY,
        json = json,
    )

    /**
     * The face-and-voice configuration, read once from `assets/agents.json`.
     *
     * An asset rather than a build field: these ids are not secrets, they change more often than the
     * code does, and an operator swapping a voice should not need a Kotlin file open to do it.
     */
    @Provides
    @Singleton
    fun agentProfiles(@ApplicationContext context: Context, json: Json): AgentProfiles =
        context.assets.open(AGENT_PROFILES_ASSET).use { stream ->
            json.decodeFromString(stream.readBytes().decodeToString())
        }

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun avatarSessionFactory(
        http: OkHttpClient,
        @ApplicationScope scope: CoroutineScope,
    ): AvatarSessionFactory = AvatarSessionFactory {
        LiveAvatarSession(http = http, apiKey = BuildConfig.LIVEAVATAR_API_KEY, scope = scope)
    }

    /**
     * One controller for the process, because it owns one LiveKit [io.livekit.android.room.Room] and
     * therefore one native `PeerConnectionFactory`. Two of those alive at once is a crash on dispose,
     * so the room is re-used across conversations rather than rebuilt per screen.
     *
     * Its scope outlives every ViewModel for the same reason: a room torn down with the screen would
     * take the EGL context with it while frames were still being decoded into it.
     */
    @Provides
    @Singleton
    fun avatarController(@ApplicationContext context: Context): AvatarController =
        LiveKitAvatarController(
            context = context,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )

    private const val AGENT_PROFILES_ASSET = "agents.json"
}
