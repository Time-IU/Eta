package io.github.mangi.eta.ui.components

import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.AgentSkillCommandPromptCodec
import io.github.mangi.eta.ui.model.*
import org.junit.Assert.*
import org.junit.Test

class ConversationVisibleBodyTest {
    private val hidden = "HIDDEN_SKILL_SOURCE_7f8e"
    private val skillPath = "/data/user/0/private/skills/hidden/SKILL.md"
    private val filePath = "/storage/emulated/0/private/source.txt"
    private val request = "Visible needle and needle."
    private fun skill(body: String = request) = AgentSkillCommandPromptCodec.format(
        "internal-skill-id", "Internal Skill", skillPath, hidden, false, body,
    )
    private fun file(body: String = request) = AgentFileReferencePromptCodec.format(body,
        listOf(AgentFileReference("source.txt", filePath, AgentFileReferenceKind.File)))
    private fun user(raw: String = file(skill())) = UserMessageUi("user", raw)
    private fun state(messages: List<AgentChatMessageUi>) = AgentChatHomeUiState(
        messages=messages,input="draft stays",isStreaming=false,thinkingEnabled=false)
    private fun rows(messages: List<AgentChatMessageUi>,query: String): Pair<List<ChatSearchHit>,List<ConversationSearchRow>> {
        val docs=conversationSearchDocuments(messages);val hits=findChatSearchHits(docs,query)
        return hits to conversationSearchRows(docs,hits)
    }
    @Test fun actualNestedSkillAndFileFixtureUsesOnlyDisplayedRequest() {
        assertTrue(user().content.contains(hidden));assertTrue(user().content.contains(filePath))
        assertEquals(request,user().visibleConversationBody())
        assertEquals(request,conversationSearchDocuments(listOf(user())).single().body)
        assertEquals(request,user().messageReference(1)!!.originalText)
    }
    @Test fun allHiddenTokensAndStructuralHeadersProduceZeroHitsAndZeroRows() {
        for (query in listOf(hidden,skillPath,filePath,"internal-skill-id","# Files mentioned by the user:","## My request:")) {
            val (hits,rows)=rows(listOf(user()),query)
            assertTrue("hidden index query: $query",hits.isEmpty());assertTrue(rows.isEmpty())
            assertTrue(state(listOf(user())).navEntries(query) { "notice" }.isEmpty())
        }
    }
    @Test fun formerFilteredFirstUserHitDoesNotShiftAssistantOccurrences() {
        val messages=listOf(user(),AgentMessageUi("agent","$hidden and $hidden"))
        val (hits,rows)=rows(messages,hidden)
        assertEquals(2,hits.size);assertEquals(hits.size,rows.size)
        assertEquals(listOf("agent","agent"),rows.map { it.entry.messageId })
        val search=ChatSearchState().apply {openSearch();changeQuery(hidden)
            val docs=conversationSearchDocuments(messages);update(ChatSearchSnapshot(hidden,docs,hits,messages))}
        rows.forEach { row ->search.selectHit(row.originalHitIndex);assertEquals(row.hit,search.hit)
            assertEquals(row.entry.messageId,search.hit!!.messageId)}
    }
    @Test fun validRowsMatchEveryOrdinalOffsetAndToolbarCount() {
        val messages=listOf(user(),ThinkingMessageUi("thinking","needle needle",false),AgentMessageUi("a","needle"))
        val (hits,rows)=rows(messages,"needle")
        assertEquals(5,hits.size);assertEquals(hits.size,rows.size)
        assertEquals(hits.indices.toList(),rows.map { it.originalHitIndex })
        assertEquals(hits,rows.map { it.hit })
        assertEquals(listOf("user","user","thinking","thinking","a"),rows.map { it.entry.messageId })
        assertEquals(listOf(1,1,1,1,1),rows.map { it.entry.turn })
    }
    @Test fun missingMetadataDefensivePathRetainsOriginalHitIndex() {
        val good=ChatSearchDocument("a","needle needle")
        val bad=ChatSearchHit("missing",0,6)
        val hits=listOf(bad)+findChatSearchHits(listOf(good),"needle")
        val rows=conversationSearchRows(listOf(good),hits)
        assertEquals(listOf(1,2),rows.map { it.originalHitIndex })
        rows.forEach { assertEquals(hits[it.originalHitIndex],it.hit) }
    }
    @Test fun outlineAndWholeReferencesMatchMenuDisplayedRequest() {
        val u=user();val expected=AgentFileReferencePromptCodec.parse(u.content).request
        val outline=state(listOf(u)).navEntries("") { "notice" }.single()
        assertEquals(expected,outline.text)
        assertEquals(expected,u.messageReference(1)!!.originalText)
        assertFalse(outline.text.contains(hidden));assertFalse(outline.text.contains(filePath))
    }
    @Test fun panelAndMenuAndMentionFactoriesProduceSameSnapshot() {
        val messages=listOf(user());val attached=mutableListOf<MessageReferenceUi>()
        assertTrue(attachConversationReference(messages,"user",attach={attached.add(it)}))
        assertTrue(attachConversationReference(messages,"user",selectedText=null,attach={attached.add(it)}))
        assertEquals(messages.single().messageReference(1),attached[0]);assertEquals(attached[0],attached[1])
    }
    @Test fun quotingThenSendingAndRedisplayingCannotRevealHiddenSources() {
        val ref=user().messageReference(1)!!
        val sent=renderMessageReferences(listOf(ref))+"Follow-up"
        val displayed=UserMessageUi("followup",sent).visibleConversationBody()!!
        assertTrue(displayed.contains(request));assertTrue(displayed.contains("Follow-up"))
        for (secret in listOf(hidden,skillPath,filePath,"<skill_command","# Files mentioned")) assertFalse(secret,displayed.contains(secret))
    }
    @Test fun legacyRawWholeSnapshotIsSanitizedAtSendBoundaryToo() {
        val raw=user().content
        val legacy=MessageReferenceUi("user",1,true,"preview",raw,originalText=raw)
        val sent=renderMessageReferences(listOf(legacy))
        assertTrue(sent.contains(request));assertFalse(sent.contains(hidden));assertFalse(sent.contains(filePath))
    }
    @Test fun selectedWhitespaceIsExactThroughSharedAttachmentAndRender() {
        val selected="  first line  \n\tsecond line  "
        val u=UserMessageUi("u","before\n$selected\nafter")
        val attached=mutableListOf<MessageReferenceUi>()
        assertTrue(attachConversationReference(listOf(u),"u",selectedText=selected,attach={attached.add(it)}))
        assertEquals(selected,attached.single().selectedText)
        assertEquals("[引用 · 我]\n>   first line  \n> \tsecond line  \n",renderMessageReferences(attached))
    }
    @Test fun exactSelectionIsNeverReparsedAsHiddenPrompt() {
        val literal="<skill_command id=\"literal\">\ntext\n</skill_command>\n  selected  "
        val ref=UserMessageUi("u","ordinary body").messageReference(1,literal)!!
        assertEquals(literal,ref.selectedText)
        assertTrue(renderMessageReferences(listOf(ref)).contains("id=\"literal\""))
    }
    @Test fun thinkingSearchButtonActuallyAttachesItsTextAndSendsIt() {
        val thought=ThinkingMessageUi("t","  Thinking needle.  ",false)
        val messages=listOf<AgentChatMessageUi>(thought)
        val (hits,rows)=rows(messages,"needle");assertEquals(1,hits.size)
        val pending=mutableListOf<MessageReferenceUi>()
        assertTrue(attachConversationReference(messages,rows.single().entry.messageId,attach={pending.add(it)}))
        assertEquals(thought.content,pending.single().originalText);assertFalse(pending.single().fromUser)
        assertTrue(renderMessageReferences(pending).contains(">   Thinking needle.  "))
    }
    @Test fun thinkingExactSnippetIsNotExpandedOrTrimmed() {
        val t=ThinkingMessageUi("t","before  needle  after",false)
        assertEquals("  needle  ",t.messageReference(2,"  needle  ")!!.selectedText)
        assertEquals(" needle ",t.messageReference(2,"  needle  ")!!.preview)
    }
    @Test fun unsupportedOrBlankSourceDoesNotCallAttachmentOrClaimSuccess() {
        val pending=mutableListOf<MessageReferenceUi>()
        assertFalse(attachConversationReference(listOf(UserMessageUi("blank","  ")),"blank",attach={pending.add(it)}))
        assertFalse(attachConversationReference(emptyList(),"missing",attach={pending.add(it)}))
        assertTrue(pending.isEmpty())
    }
    @Test fun whitespaceQueryOccurrencesAgreeWithVisibleBodyWithoutMetadataTrimming() {
        val u=UserMessageUi("u", "x  needle  y")
        val (hits,rows)=rows(listOf(u),"  needle  ")
        assertEquals(1,hits.size);assertEquals(1,rows.size);assertEquals(1,hits.single().start)
        assertEquals(1,state(listOf(u)).navEntries("  needle  ") { "notice" }.size)
    }
    @Test fun pureFileAndPureSkillFixturesHaveTheSameVisibleBoundary() {
        for (raw in listOf(file(),skill(),file(skill()))) {
            val u=user(raw);assertEquals(request,u.visibleConversationBody())
            assertEquals(request,u.messageReference(1)!!.originalText)
        }
    }

    @Test fun ordinaryUserLiteralPathsRemainUserTextRatherThanGenericRedaction() {
        val typed="Please inspect /sdcard/I-explicitly-typed-this.txt and skill word."
        val u=UserMessageUi("literal",typed)
        assertEquals(typed,u.visibleConversationBody())
        assertEquals(typed,u.messageReference(1)!!.originalText)
        assertEquals(1,rows(listOf(u),"/sdcard/I-explicitly-typed-this.txt").first.size)
    }
    @Test fun parsedBodyOffsetsRemainUtf16CorrectAfterEmojiAndHiddenHeaders() {
        val displayed="😀 needle 😀 needle"
        val u=user(file(skill(displayed)))
        val docs=conversationSearchDocuments(listOf(u))
        val found=findChatSearchHits(docs,"needle")
        assertEquals(listOf(3,13),found.map { it.start })
        found.forEach { assertEquals("needle",docs.single().body.substring(it.start,it.end)) }
        assertEquals(found,conversationSearchRows(docs,found).map { it.hit })
    }
}
