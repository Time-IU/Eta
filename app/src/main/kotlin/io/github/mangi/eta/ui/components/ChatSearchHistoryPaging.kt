package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import io.github.mangi.eta.ui.model.ConversationHistoryLoadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private data class ChatSearchHistoryConfiguration(
    val canLoad: Boolean,
    val hasEarlier: Boolean,
    val before: Int,
    val loadState: ConversationHistoryLoadState,
)

@Stable
internal class ChatSearchHistoryPagingState {
    // Retain this guard through query changes/closing; the storage callback is not cancellable
    // from this surface. A previously issued page may still be completing in AgentAppState.
    var requestedBefore by mutableStateOf<Int?>(null)
    var retryVersion by mutableIntStateOf(0)
        private set
    var handledRetryVersion by mutableIntStateOf(0)
    var awaiting by mutableStateOf(false)
    var stalled by mutableStateOf(false)
    fun retry() { retryVersion++ }
}

/** Sequential demand producer, not a second storage loader. AgentAppState owns the one page job.
 * Its guards reject loads while streaming/editing; suspend here until those guards allow progress.
 * Cancellation stops future requests, not the already issued storage transaction.
 */
@Composable
internal fun rememberChatSearchHistoryPaging(
    open: Boolean,
    query: String,
    canLoad: Boolean,
    hasEarlier: Boolean,
    before: Int,
    loadState: ConversationHistoryLoadState,
    onLoadEarlier: () -> Unit,
    includeOutline: Boolean = false,
): ChatSearchHistoryPagingState {
    val state = remember { ChatSearchHistoryPagingState() }
    val configuration by rememberUpdatedState(ChatSearchHistoryConfiguration(canLoad, hasEarlier, before, loadState))
    val loadEarlier by rememberUpdatedState(onLoadEarlier)
    if (open && (includeOutline || query.isNotBlank())) LaunchedEffect(query, includeOutline, state.retryVersion) {
        try {
            while (true) {
                val ready = snapshotFlow { configuration }.first { current ->
                    current.loadState != ConversationHistoryLoadState.Loading &&
                        (current.canLoad || !current.hasEarlier)
                }
                val decision = chatSearchPagingDecision(
                    open = open, query = query, includeOutline = includeOutline, canLoad = ready.canLoad,
                    hasEarlier = ready.hasEarlier, before = ready.before, loadState = ready.loadState,
                    requestedBefore = state.requestedBefore,
                    retry = state.retryVersion != state.handledRetryVersion,
                )
                if (decision != ChatSearchPagingDecision.Request) {
                    state.stalled = decision == ChatSearchPagingDecision.Stalled
                    return@LaunchedEffect
                }
                // Claim before calling out, including when Loading is not observed in this frame.
                state.requestedBefore = ready.before
                state.handledRetryVersion = state.retryVersion
                state.stalled = false
                state.awaiting = true
                loadEarlier()
                // Only time out an unacknowledged callback, not a real storage transaction.
                // Large conversations may legitimately take a long time to persist/load a page.
                val acknowledged = withTimeoutOrNull(5_000L) {
                    snapshotFlow { configuration }.first { current ->
                        current.loadState != ConversationHistoryLoadState.Idle ||
                            current.before != ready.before || !current.hasEarlier
                    }
                }
                if (acknowledged == null) {
                    state.stalled = true
                    return@LaunchedEffect
                }
                var sawLoading = acknowledged.loadState == ConversationHistoryLoadState.Loading
                val finished = if (chatSearchPageFinished(ready.before, acknowledged.before,
                        acknowledged.hasEarlier, acknowledged.loadState, sawLoading)) acknowledged else {
                    snapshotFlow { configuration }.first { current ->
                        if (current.loadState == ConversationHistoryLoadState.Loading) sawLoading = true
                        chatSearchPageFinished(ready.before, current.before, current.hasEarlier, current.loadState, sawLoading)
                    }
                }
                state.awaiting = false
                if (finished.hasEarlier && finished.before >= ready.before &&
                        finished.loadState != ConversationHistoryLoadState.Failed) {
                    // Ignored/rejected/no-progress callbacks require an explicit retry.
                    state.stalled = true
                    return@LaunchedEffect
                }
                // Let the previous owner's job finish and allow a frame/input between pages.
                withFrameNanos { }
            }
        } finally {
            state.awaiting = false
        }
    }
    return state
}
