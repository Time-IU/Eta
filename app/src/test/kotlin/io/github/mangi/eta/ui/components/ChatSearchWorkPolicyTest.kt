package io.github.mangi.eta.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatSearchWorkPolicyTest {
    @Test fun thousandClosedUpdatesVisitZeroMessages() {
        val messages = List(10_000) { it }
        var visits = 0
        repeat(1_000) {
            assertFalse(chatSearchWorkEnabled(false, "match"))
            val docs = buildActiveChatSearchDocuments(false, "match", messages) {
                visits++; ChatSearchDocument(it.toString(), "match")
            }
            assertEquals(0, docs.size)
        }
        assertEquals(0, visits)
    }
    @Test fun blankOpenSearchVisitsZeroMessages() {
        var visits = 0
        buildActiveChatSearchDocuments(true, " ", listOf(1, 2, 3)) {
            visits++; ChatSearchDocument(it.toString(), "text")
        }
        assertEquals(0, visits)
    }
    @Test fun openSearchVisitsAllLoadedMessagesNotJustVisibleRows() {
        var visits = 0
        val docs = buildActiveChatSearchDocuments(true, "match", List(10_000) { it }) {
            visits++; ChatSearchDocument(it.toString(), "match")
        }
        assertEquals(10_000, visits)
        assertEquals(10_000, findChatSearchHits(docs, "match").size)
    }
}
