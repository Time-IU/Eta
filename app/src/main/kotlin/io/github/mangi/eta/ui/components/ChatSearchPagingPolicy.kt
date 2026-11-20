package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.ConversationHistoryLoadState

internal enum class ChatSearchPagingDecision { Inactive, Complete, Loading, Paused, Failed, Stalled, Request }

/** One request per exclusive history cursor. Only an explicit retry may reuse that cursor.
 * A new query must not accidentally retry an old failure or duplicate an unacknowledged request.
 */
@JvmOverloads
internal fun chatSearchPagingDecision(
    open: Boolean,
    query: String,
    canLoad: Boolean,
    hasEarlier: Boolean,
    before: Int,
    loadState: ConversationHistoryLoadState,
    requestedBefore: Int?,
    retry: Boolean = false,
    includeOutline: Boolean = false,
): ChatSearchPagingDecision = when {
    !open || (!includeOutline && query.isBlank()) -> ChatSearchPagingDecision.Inactive
    loadState == ConversationHistoryLoadState.Loading -> ChatSearchPagingDecision.Loading
    !hasEarlier -> ChatSearchPagingDecision.Complete
    !canLoad -> ChatSearchPagingDecision.Paused
    loadState == ConversationHistoryLoadState.Failed && !retry -> ChatSearchPagingDecision.Failed
    requestedBefore == before && !retry -> ChatSearchPagingDecision.Stalled
    else -> ChatSearchPagingDecision.Request
}

/** A completed callback without cursor progress is not success and must never trigger a loop. */
internal fun chatSearchPageFinished(
    before: Int,
    currentBefore: Int,
    hasEarlier: Boolean,
    loadState: ConversationHistoryLoadState,
    sawLoading: Boolean,
): Boolean = loadState == ConversationHistoryLoadState.Failed ||
    (loadState != ConversationHistoryLoadState.Loading &&
        (!hasEarlier || currentBefore < before || sawLoading))
