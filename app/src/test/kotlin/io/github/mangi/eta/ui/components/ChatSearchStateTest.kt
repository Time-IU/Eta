package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSearchStateTest {
    private fun result(body: String, query: String = "one", messageId: String = "agent"): ChatSearchSnapshot {
        val documents = listOf(ChatSearchDocument(messageId, body))
        return ChatSearchSnapshot(query, documents, findChatSearchHits(documents, query))
    }

    @Test fun closeClearsQuerySelectionAndResultsAndReopenStartsEmpty() {
        val state = ChatSearchState()
        state.openSearch()
        state.changeQuery("one")
        state.update(result("one one"))
        state.move(1)
        assertEquals(1, state.index)
        state.close()
        assertFalse(state.open)
        assertEquals("", state.query)
        assertNull(state.hit)
        assertNull(state.preview)
        assertNull(state.snapshot)
        state.openSearch()
        assertTrue(state.hits.isEmpty())
    }

    @Test fun changingQueryNeverExposesOldResultsWhileWorkerRuns() {
        val state = ChatSearchState()
        state.openSearch()
        state.changeQuery("one")
        state.update(result("one one"))
        state.changeQuery("two")
        assertTrue(state.searching)
        assertNull(state.hit)
        assertNull(state.preview)
        state.update(result("two two", "two"))
        assertFalse(state.searching)
        assertEquals(0, state.index)
    }

    @Test fun lateWorkerCannotRestoreClosedSearchOrOldQuerySelection() {
        val state = ChatSearchState()
        state.openSearch()
        state.changeQuery("two")
        state.update(result("prefix one"))
        assertNull(state.snapshot)
        state.update(result("two two", "two"))
        assertEquals(0, state.index)
        state.close()
        state.update(result("two two", "two"))
        assertNull(state.snapshot)
        assertTrue(state.hits.isEmpty())
    }

    @Test fun selectedOccurrenceSurvivesStreamingAndCanBeLocatedRepeatedly() {
        val state = ChatSearchState()
        state.openSearch()
        state.changeQuery("one")
        state.update(result("one one"))
        state.move(1)
        val version = state.navigationVersion
        val anchor = state.hit?.anchor
        state.update(result("one one one"))
        assertEquals(3, state.hits.size)
        assertEquals(anchor, state.hit?.anchor)
        assertEquals(version, state.navigationVersion)
        state.locate()
        assertEquals(version + 1, state.navigationVersion)
    }

    @Test fun historyPrependsChangeOrdinalWithoutMovingOccurrenceOrIssuingNavigation() {
        val state = ChatSearchState()
        state.openSearch()
        state.changeQuery("one")
        state.update(result("one one"))
        state.move(1)
        val originalAnchor = state.hit?.anchor
        val navigationVersion = state.navigationVersion
        val documents = listOf(ChatSearchDocument("earlier", "one one one"), ChatSearchDocument("agent", "one one"))
        state.update(ChatSearchSnapshot("one", documents, findChatSearchHits(documents, "one")))
        assertEquals(4, state.index)
        assertEquals(originalAnchor, state.hit?.anchor)
        assertEquals(navigationVersion, state.navigationVersion)
    }

    @Test fun conversationCompositionsOwnIndependentSearchState() {
        val first = ChatSearchState()
        first.openSearch()
        first.changeQuery("one")
        first.update(result("one one"))
        val second = ChatSearchState()
        assertFalse(second.open)
        assertEquals("", second.query)
        assertTrue(second.hits.isEmpty())
    }

    @Test fun deletedSelectedMessageLeavesNoStalePreview() {
        val state = ChatSearchState()
        state.openSearch()
        state.changeQuery("one")
        state.update(result("one"))
        state.update(result("unrelated"))
        assertNull(state.hit)
        assertNull(state.preview)
        assertEquals(-1, state.index)
    }

    @Test fun autoPagingKeepsExistingProcessContentsAndKeysIntactForViewportAnchoring() {
        val existing = ThinkingMessageUi("existing", "already reading", isStreaming = false)
        val answer = AgentMessageUi("answer", "result")
        val previous = listOf(existing, answer).toTimelineEntries()
        val prepended = listOf(ThinkingMessageUi("earlier", "newly loaded", isStreaming = false), existing, answer)
        val next = prepended.toTimelineEntries(previous, preservePreviousWorkBoundaries = true)
        assertEquals(3, next.size)
        assertEquals(previous[0].key, next[1].key)
        assertEquals(listOf(existing), (next[1] as AgentTimelineEntry.WorkProcess).messages)
        assertEquals(previous[1].key, next[2].key)
        assertEquals(2, prepended.toTimelineEntries(previous).size) // Normal grouping unchanged.
    }

    @Test fun multipleAutomaticPagesRetainAllExistingWorkBoundaries() {
        val old = ThinkingMessageUi("old", "old", isStreaming = false)
        val middle = ThinkingMessageUi("middle", "middle", isStreaming = false)
        val newest = ThinkingMessageUi("newest", "newest", isStreaming = false)
        val initial = listOf(newest).toTimelineEntries()
        val pageOne = listOf(middle, newest).toTimelineEntries(initial, preservePreviousWorkBoundaries = true)
        val pageTwo = listOf(old, middle, newest).toTimelineEntries(pageOne, preservePreviousWorkBoundaries = true)
        assertEquals(pageOne.map { it.key }, pageTwo.drop(1).map { it.key })
        assertEquals(listOf("old", "middle", "newest"), pageTwo.map {
            (it as AgentTimelineEntry.WorkProcess).messages.single().id
        })
    }

    @Test fun mapsBodyHitsToOriginalAndCollapsedProcessTimelineItems() {
        val entries = listOf(
            UserMessageUi("user", "one"),
            ThinkingMessageUi("thinking", "one", isStreaming = false, collapsed = true),
            AgentMessageUi("assistant", "one"),
        ).toTimelineEntries()
        assertEquals(0, chatSearchTimelineIndex(entries, "user"))
        assertEquals(1, chatSearchTimelineIndex(entries, "thinking"))
        assertEquals(2, chatSearchTimelineIndex(entries, "assistant"))
        assertEquals(-1, chatSearchTimelineIndex(entries, "deleted"))
        assertEquals(-1, chatSearchTimelineIndex(entries, null))
    }
}
