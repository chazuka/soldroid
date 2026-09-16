package id.ocbc.chatty

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import id.ocbc.chatty.core.ai.Language
import java.util.Locale

/**
 * The language the app is written in, chosen in the app rather than in Android's settings.
 *
 * # Why the switch and not the handset
 *
 * The customer is already choosing a language — the switch on the stage and in the thread picks the
 * recogniser, the voice and the number speller. Having the *words on the screen* follow a different
 * setting, buried two levels into Android's own settings, is two languages in one product: an
 * English conversation conducted through Indonesian buttons. So the switch decides both, and it is
 * reachable from every screen.
 *
 * # What this is not
 *
 * It is not the conversation's language. That one lives in [id.ocbc.chatty.companion.CompanionUiState]
 * and is allowed to move on its own: when the model answers in the other language, the voice and the
 * number speller follow the answer, because the alternative is an English sentence read by an
 * Indonesian voice through a speller that says "Rp1,200,000" as "satu koma dua rupiah". That
 * correction is per-answer and belongs to the conversation. It deliberately does not reach here —
 * one question asked in English should not silently rewrite every button in the app.
 *
 * The switch writes to both. Detection writes only to the conversation.
 */
/**
 * The language the app is written in, for anything that has to *show* it rather than read it.
 *
 * The switch is the only control over this, so the control has to show this and not the
 * conversation's own language — those two part company the moment the model answers in the other
 * language, and a switch that flips itself to ID while every word on the screen is still English is
 * a switch that is lying about what it does. Read it with `LocalAppLanguage.current`.
 */
val LocalAppLanguage = staticCompositionLocalOf<Language> {
    error("no app language; wrap the screen in ProvideAppLanguage")
}

@Composable
fun ProvideAppLanguage(language: Language, content: @Composable () -> Unit) {
    val context = LocalContext.current

    // Keyed on the configuration as well as the language, and that is not a detail.
    //
    // The context built here carries a *copy* of the configuration taken when it was built. Keyed
    // on the language alone it was never rebuilt, so everything inside the app went on reading the
    // orientation, size and density the app happened to launch in — a rotated handset kept
    // reporting itself as portrait, and every `LocalConfiguration` decision downstream quietly
    // stopped working. Rebuilding when the real configuration changes is what keeps the override
    // to the one thing it is supposed to override.
    val configuration = LocalConfiguration.current
    val localized = remember(context, language, configuration) { context.localizedFor(language) }

    // All three, because different call sites read different ones. `stringResource` goes through
    // LocalResources; a drawable looked up by name — see `AgentPoster` — goes through LocalContext;
    // anything reading the configuration directly gets the matching one rather than a stale copy.
    CompositionLocalProvider(
        LocalAppLanguage provides language,
        LocalContext provides localized,
        LocalResources provides localized.resources,
        LocalConfiguration provides localized.resources.configuration,
        content = content,
    )
}

/**
 * A [Context] whose resources resolve in [language].
 *
 * Also the way anything outside the composition gets the right words — a notification is built by a
 * Service, which has no composition to read a local from.
 *
 * ```
 * val words = context.localizedFor(language)
 * builder.setContentText(words.getString(R.string.conversation_ongoing_detail))
 * ```
 *
 * # Why this wraps instead of returning the configuration context
 *
 * `createConfigurationContext` hands back a bare `ContextImpl`, and a `ContextImpl` is not an
 * Activity. Provided as `LocalContext` that crashes the app on the first screen that asks for a view
 * model: *"Expected an activity context for creating a HiltViewModelFactory but instead found:
 * android.app.ContextImpl"*. Hilt finds the Activity by walking the `ContextWrapper` chain, so the
 * localized context has to be a link in that chain rather than a replacement for it — the resources
 * are swapped, and everything else still leads back to the Activity.
 */
fun Context.localizedFor(language: Language): Context {
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(Locale.forLanguageTag(language.tag))
    return LocalizedContext(this, createConfigurationContext(configuration).resources)
}

/** A [Context] that is its base in every respect except the language its resources are in. */
private class LocalizedContext(base: Context, private val localized: Resources) : ContextWrapper(base) {
    override fun getResources(): Resources = localized
}

/**
 * Which language to start in, read once from the handset.
 *
 * English only when the handset is set to English; everything else lands on Indonesian, which is the
 * language these customers and their accounts are in and the one `values/` is written in. Read at
 * startup and then left alone — after that the switch is the authority, and a handset that changes
 * language underneath a running conversation should not reach in and change it.
 */
fun deviceLanguage(context: Context): Language {
    val tag = context.resources.configuration.locales[0]?.language
    return if (tag == Locale.ENGLISH.language) Language.ENGLISH else Language.INDONESIAN
}
