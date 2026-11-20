package io.github.mangi.eta.ui.components
import org.junit.Assert.*
import org.junit.Test
class ConversationSearchStateTest {
    private val docs = listOf(ChatSearchDocument("m", "aaa"), ChatSearchDocument("n", "later a"))
    private fun state(): ChatSearchState = ChatSearchState().apply {
        openSearch();changeQuery("a"); update(ChatSearchSnapshot("a",docs,findChatSearchHits(docs,"a")))
    }
    @Test fun rowSelectsExactOccurrenceNotJustMessage() {
        val s=state();s.selectHit(2); assertEquals(2,s.hit!!.start);assertEquals("m",s.hit!!.messageId)
        assertTrue(s.navigationVersion>0)
    }
    @Test fun previousAndNextWrapAllOccurrences() {
        val s=state();s.move(-1);assertEquals(s.hits.last(),s.hit);s.move(1);assertEquals(s.hits.first(),s.hit)
    }
    @Test fun prependedHistoryPreservesExactOccurrence() {
        val s=state();s.selectHit(1);val previous=s.hit
        val newDocs=listOf(ChatSearchDocument("older","a"))+docs
        s.update(ChatSearchSnapshot("a",newDocs,findChatSearchHits(newDocs,"a")))
        assertEquals(previous,s.hit);assertEquals(2,s.index)
    }
    @Test fun closedSearchRejectsWorkerAndHasZeroResults() {
        val s=state();val result=s.snapshot!!;s.close();s.update(result)
        assertFalse(s.open);assertEquals("",s.query);assertNull(s.snapshot);assertTrue(s.hits.isEmpty())
    }
    @Test fun changedQueryRejectsStaleWorker() {
        val s=state();val stale=s.snapshot!!;s.changeQuery("later");s.update(stale)
        assertTrue(s.hits.isEmpty());assertTrue(s.searching)
    }
    @Test fun invalidRowCannotChangeSelection() {
        val s=state();val before=s.hit;s.selectHit(999);assertEquals(before,s.hit)
    }
}
