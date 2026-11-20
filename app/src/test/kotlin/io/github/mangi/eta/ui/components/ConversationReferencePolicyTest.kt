package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.*
import org.junit.Assert.*
import org.junit.Test

class ConversationReferencePolicyTest {
    private fun ref(id: String = "private-internal-id", selected: String? = null, body: String = "First. 第二句。\nLast.") =
        MessageReferenceUi(id, 4, false, selected ?: body.take(60), body.take(4000), selected, body)
    @Test fun entireBodyIsSentWithoutTruncationOrInternalIdentifiers() {
        val body = "x".repeat(6000) + "THE-END"
        val sent = renderMessageReferences(listOf(ref(body = body)))
        assertTrue(sent.contains(body)); assertFalse(sent.contains("private-internal-id"))
    }
    @Test fun selectedSnapshotIsExactAndNeverExpandsToWholeMessage() {
        val sent = renderMessageReferences(listOf(ref(selected = "第二句。"))) { "changed" }
        assertTrue(sent.contains("> 第二句。")); assertFalse(sent.contains("First.")); assertFalse(sent.contains("changed"))
    }
    @Test fun wholeAndTwoDistinctExcerptsCoexist() {
        val a=ref();val b=ref(selected="First.");val c=ref(selected="第二句。")
        assertEquals(MessageReferenceDecision.Attach,messageReferenceDecision(listOf(a,b),c))
        assertEquals(3,listOf(a,b,c).map { it.referenceKey }.toSet().size)
    }
    @Test fun exactExcerptDuplicateIgnoredEvenAtLimit() {
        val a=ref(selected="First.")
        assertEquals(MessageReferenceDecision.Duplicate,messageReferenceDecision(listOf(ref(),a,ref(selected="Last.")),a.copy(preview="different")))
    }
    @Test fun fourthDistinctReferenceRejected() {
        assertEquals(MessageReferenceDecision.Limit,messageReferenceDecision(listOf(ref("a"),ref("b"),ref("c")),ref("d")))
    }
    @Test fun sameExcerptFromAnotherMessageIsNotDuplicate() {
        assertEquals(MessageReferenceDecision.Attach,messageReferenceDecision(listOf(ref("a","same")),ref("b","same")))
    }
    @Test fun pagingOrEditingCannotChangeCapturedWholeBody() {
        assertTrue(renderMessageReferences(listOf(ref(body="original"))) { "edited" }.contains("> original"))
    }
    @Test fun emptyLegacySnapshotCanResolveExistingOriginal() {
        assertTrue(renderMessageReferences(listOf(ref(body=""))) { "restored original" }.contains("> restored original"))
    }
    @Test fun blankSelectedBodyIsNotSubstitutedWithWholeBody() {
        assertEquals("",renderMessageReferences(listOf(ref(selected=" "))))
    }
    @Test fun consecutiveReferencesPreserveLineBoundariesAndRoles() {
        val sent=renderMessageReferences(listOf(ref(body="a\r\nb"),ref("u",body="c").copy(fromUser=true)))
        assertTrue(sent.contains("> a\n> b"));assertTrue(sent.contains("[引用 · 我]"));assertFalse(sent.contains("\r"))
    }

    @Test fun emptyOutlinePagesAllHistoryOnlyWhileVisible() {
        val idle = ConversationHistoryLoadState.Idle
        assertEquals(ChatSearchPagingDecision.Request, chatSearchPagingDecision(true,"",true,true,100,idle,null,includeOutline=true))
        assertEquals(ChatSearchPagingDecision.Inactive, chatSearchPagingDecision(false,"",true,true,50,idle,100,includeOutline=true))
        assertEquals(ChatSearchPagingDecision.Stalled, chatSearchPagingDecision(true,"",true,true,100,idle,100,includeOutline=true))
        assertEquals(ChatSearchPagingDecision.Request, chatSearchPagingDecision(true,"",true,true,100,idle,100,retry=true,includeOutline=true))
    }
}
