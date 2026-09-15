package id.ocbc.chatty.companion

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Hands the singleton [ConversationSignals] to a composable.
 *
 * A view model with no state of its own, existing only because a `@Composable` cannot ask Hilt for a
 * singleton directly. Cheaper than threading the dependency down from the activity through every
 * caller, and it keeps the signal's lifetime where it belongs — the singleton's, not the screen's.
 */
@HiltViewModel
class ConversationSignalsHolder @Inject constructor(val signals: ConversationSignals) : ViewModel()
