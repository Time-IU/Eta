package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.ConversationHistoryLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchPagingPolicyTest {
    private fun decision(
        open: Boolean = true,
        query: String = "目标",
        canLoad: Boolean = true,
        hasEarlier: Boolean = true,
        before: Int = 100,
        loadState: ConversationHistoryLoadState = ConversationHistoryLoadState.Idle,
        requested: Int? = null,
        retry: Boolean = false,
    ) = chatSearchPagingDecision(open, query, canLoad, hasEarlier, before, loadState, requested, retry)

    @Test fun onlyOpenNonblankSearchRequestsHistory() {
        assertEquals(ChatSearchPagingDecision.Request, decision())
        assertEquals(ChatSearchPagingDecision.Inactive, decision(open = false))
        listOf("", " ", "\n\t").forEach {
            assertEquals(ChatSearchPagingDecision.Inactive, decision(query = it))
        }
    }

    @Test fun existingPageJobBlocksEvenAnExplicitRetry() {
        assertEquals(ChatSearchPagingDecision.Loading, decision(loadState = ConversationHistoryLoadState.Loading))
        assertEquals(ChatSearchPagingDecision.Loading,
            decision(loadState = ConversationHistoryLoadState.Loading, retry = true))
    }

    @Test fun claimsEachCursorOnceEvenBeforeLoadingIsObserved() {
        assertEquals(ChatSearchPagingDecision.Request, decision(before = 100))
        repeat(20) {
            assertEquals(ChatSearchPagingDecision.Stalled, decision(before = 100, requested = 100))
        }
    }

    @Test fun sequentialPagesContinueOnlyAfterAcknowledgedProgress() {
        var requested: Int? = null
        for (cursor in listOf(300, 200, 100)) {
            assertEquals(ChatSearchPagingDecision.Request, decision(before = cursor, requested = requested))
            requested = cursor
            assertEquals(ChatSearchPagingDecision.Loading,
                decision(before = cursor, requested = requested, loadState = ConversationHistoryLoadState.Loading))
            assertEquals(ChatSearchPagingDecision.Stalled, decision(before = cursor, requested = requested))
        }
        assertEquals(ChatSearchPagingDecision.Complete, decision(before = 0, hasEarlier = false, requested = requested))
    }

    @Test fun failureNeverAutomaticallyRetriesEvenWithAChangedQueryOrCursor() {
        assertEquals(ChatSearchPagingDecision.Failed,
            decision(loadState = ConversationHistoryLoadState.Failed))
        assertEquals(ChatSearchPagingDecision.Failed,
            decision(query = "new query", before = 80, requested = 100, loadState = ConversationHistoryLoadState.Failed))
    }

    @Test fun explicitRetryCanRecoverFailedOrNoProgressPageExactlyOnce() {
        assertEquals(ChatSearchPagingDecision.Request,
            decision(requested = 100, loadState = ConversationHistoryLoadState.Failed, retry = true))
        assertEquals(ChatSearchPagingDecision.Request, decision(requested = 100, retry = true))
        assertEquals(ChatSearchPagingDecision.Stalled, decision(requested = 100))
    }

    @Test fun closeStopsFurtherRequestsEvenWhenPreviousPageJustFinished() {
        assertEquals(ChatSearchPagingDecision.Inactive, decision(open = false, before = 50, requested = 100))
        assertEquals(ChatSearchPagingDecision.Inactive, decision(open = false, retry = true))
    }

    @Test fun newQueryCannotDuplicateUnacknowledgedOldQueryPage() {
        assertEquals(ChatSearchPagingDecision.Stalled, decision(query = "changed", requested = 100))
        assertEquals(ChatSearchPagingDecision.Loading,
            decision(query = "changed", requested = 100, loadState = ConversationHistoryLoadState.Loading))
        assertEquals(ChatSearchPagingDecision.Request, decision(query = "changed", before = 50, requested = 100))
    }

    @Test fun streamingOrEditingPausesButDoesNotClaimAPage() {
        assertEquals(ChatSearchPagingDecision.Paused, decision(canLoad = false))
        assertEquals(ChatSearchPagingDecision.Paused, decision(canLoad = false, retry = true))
        assertEquals(ChatSearchPagingDecision.Request, decision(canLoad = true))
    }

    @Test fun exhaustedHistoryStopsRegardlessOfQueryOrRetry() {
        assertEquals(ChatSearchPagingDecision.Complete, decision(hasEarlier = false))
        assertEquals(ChatSearchPagingDecision.Complete, decision(hasEarlier = false, canLoad = false, retry = true))
    }

    @Test fun independentConversationStartsWithIndependentCursorGuard() {
        assertEquals(ChatSearchPagingDecision.Stalled, decision(requested = 100))
        assertEquals(ChatSearchPagingDecision.Request, decision(requested = null))
    }

    @Test fun loadingMustSettleBeforeTheNextPageEvenIfCursorAlreadyChanged() {
        assertFalse(chatSearchPageFinished(100, 50, true, ConversationHistoryLoadState.Loading, true))
        assertTrue(chatSearchPageFinished(100, 50, true, ConversationHistoryLoadState.Idle, true))
    }

    @Test fun fastPageCanFinishWithoutObservingTransientLoading() {
        assertTrue(chatSearchPageFinished(100, 50, true, ConversationHistoryLoadState.Idle, false))
        assertTrue(chatSearchPageFinished(100, 0, false, ConversationHistoryLoadState.Idle, false))
    }

    @Test fun noProgressCompletionStopsInsteadOfSpinning() {
        assertFalse(chatSearchPageFinished(100, 100, true, ConversationHistoryLoadState.Idle, false))
        assertTrue(chatSearchPageFinished(100, 100, true, ConversationHistoryLoadState.Idle, true))
        assertEquals(ChatSearchPagingDecision.Stalled, decision(requested = 100))
    }

    @Test fun loadFailureIsTerminalForTheAttemptNotConversationCompletion() {
        assertTrue(chatSearchPageFinished(100, 100, true, ConversationHistoryLoadState.Failed, true))
        assertEquals(ChatSearchPagingDecision.Failed,
            decision(requested = 100, loadState = ConversationHistoryLoadState.Failed))
    }

    @Test fun cursorMovingBackwardsCannotMasqueradeAsSuccessfulEarlierLoad() {
        assertFalse(chatSearchPageFinished(100, 200, true, ConversationHistoryLoadState.Idle, false))
        assertTrue(chatSearchPageFinished(100, 200, true, ConversationHistoryLoadState.Idle, true))
    }
}
