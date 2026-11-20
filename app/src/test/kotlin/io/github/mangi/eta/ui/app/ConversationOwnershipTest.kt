package io.github.mangi.eta.ui.app

import io.github.mangi.eta.ui.model.*
import io.github.mangi.eta.agent.model.AgentModelClient.ConversationMessage
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.roleplay.RoleplayBinding
import io.github.mangi.eta.agent.roleplay.RoleplayMessageLink
import io.github.mangi.eta.agent.roleplay.RoleplayMessageState
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ConversationOwnershipTest {
    private fun ref(id:String)=MessageReferenceUi(id,1,true,id,originalText=id)
    private val image=PendingImageUi("img","uri","data:image/png;base64,AA==","image/png")
    private val file=PendingFileReferenceUi("file",AgentFileReference("x","/sdcard/x",AgentFileReferenceKind.File))
    private fun draft()=AgentChatHomeUiState(messages=emptyList(),input="/skill original",isStreaming=false,
        thinkingEnabled=false,pendingMessageRefs=listOf(ref("source-ref")),pendingImages=listOf(image),pendingFileReferences=listOf(file))
    private fun ticket(s:AgentChatHomeUiState=draft())=SkillSubmissionOwnership.capture("A",7,3,s)
    private fun ticketMatches(s:AgentChatHomeUiState=draft(),live:String=s.input,id:String?="A",selection:Long=7,submission:Long=3)=
        ticket().matches(id,selection,submission,s,live)
    @Test fun unchangedSourceAcceptsSubmittedSnapshot() {assertTrue(ticketMatches())}
    @Test fun sourceToOtherAndFreshConversationReject() {
        assertFalse(ticketMatches(id="B"));assertFalse(ticketMatches(id=null))
    }
    @Test fun switchAwayAndBackRejectsEvenSameDraft() {assertFalse(ticketMatches(selection=9))}
    @Test fun newerSkillSubmissionInvalidatesOldTicket() {assertFalse(ticketMatches(submission=4))}
    @Test fun stateAndLiveComposerChangesBothReject() {
        assertFalse(ticketMatches(s=draft().copy(input="new draft")))
        assertFalse(ticketMatches(live="typed locally while IO was reading"))
        assertFalse(ticketMatches(live=""))
    }
    @Test fun referencesImagesFilesAndEditOwnershipAreGuarded() {
        assertFalse(ticketMatches(s=draft().copy(pendingMessageRefs=listOf(ref("new")))))
        assertFalse(ticketMatches(s=draft().copy(pendingImages=emptyList())))
        assertFalse(ticketMatches(s=draft().copy(pendingFileReferences=emptyList())))
        assertFalse(ticketMatches(s=draft().copy(messageEdit=MessageEditUiState("m","",emptyList(),emptyList(),false))))
    }
    @Test fun syncAndResetChangesInvalidateButStreamingProgressDoesNot() {
        assertFalse(ticketMatches(s=draft().copy(inputSyncVersion=1)))
        assertFalse(ticketMatches(s=draft().copy(inputResetToken=1)))
        assertTrue(ticketMatches(s=draft().copy(isStreaming=true,messages=listOf(AgentMessageUi("a","progress")))))
    }
    private fun barrierScenario(currentId:String?,selectedVersion:Long,current:AgentChatHomeUiState,live:String=current.input):Pair<Boolean,AgentChatHomeUiState> {
        val source=draft();val ownership=ticket(source)
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val load=CompletableFuture.supplyAsync { entered.countDown();check(release.await(5,TimeUnit.SECONDS));"loaded skill payload" }
        assertTrue(entered.await(5,TimeUnit.SECONDS))
        // Selection/draft replacement happens while the real IO barrier is closed.
        var b=current;var sent=false
        release.countDown();assertEquals("loaded skill payload",load.get(5,TimeUnit.SECONDS))
        if(ownership.matches(currentId,selectedVersion,3,b,live)) {
            sent=true;b=b.copy(input="",pendingMessageRefs=emptyList())
        }
        assertEquals("/skill original",source.input);assertEquals(listOf(ref("source-ref")),source.pendingMessageRefs)
        return sent to b
    }
    @Test fun ioBarrierSwitchToBPreservesBDraftAndRefs() {
        val b=draft().copy(input="B draft",pendingMessageRefs=listOf(ref("B reference")))
        val result=barrierScenario("B",8,b);assertFalse(result.first);assertEquals(b,result.second)
    }
    @Test fun ioBarrierStreamingBIsNotQueuedAndBRefsStayOwned() {
        val b=draft().copy(input="streaming B",isStreaming=true,pendingMessageRefs=listOf(ref("B")))
        val result=barrierScenario("B",8,b);assertFalse(result.first);assertEquals(b,result.second);assertTrue(result.second.queuedMessages.isEmpty())
    }
    @Test fun ioBarrierFreshConversationAndReturnToAAbort() {
        assertFalse(barrierScenario(null,8,draft().copy(input="new draft")).first)
        assertFalse(barrierScenario("A",9,draft()).first)
    }
    @Test fun ioBarrierChangedLocalDraftDoesNotConsumeOriginalOrNewRefs() {
        val a=draft();val result=barrierScenario("A",7,a,"changed local text")
        assertFalse(result.first);assertEquals(a,result.second)
    }
    @Test fun ioBarrierUnchangedOwnerSendsOnceAndConsumesOnlyCapturedRefs() {
        val result=barrierScenario("A",7,draft());assertTrue(result.first);assertTrue(result.second.pendingMessageRefs.isEmpty())
    }
    private fun roleplay():AgentChatHomeUiState {
        val journal=listOf(ConversationMessage("assistant","original",messageId="m"),ConversationMessage("user","later",messageId="later"))
        return draft().copy(messages=listOf(AgentMessageUi("m","original"),UserMessageUi("later","later")),history=journal,journal=journal,
            roleplay=RoleplayBinding("character","{}","role"),
            roleplayMessages=RoleplayMessageState(links=mapOf("m" to RoleplayMessageLink("m"),"later" to RoleplayMessageLink("later"))))
    }
    @Test fun enterRoleEditSavesAndIsolatesAllOriginalDraftAttachments() {
        val old=roleplay();val editing=RoleplayConversationReducer.beginEditDraft(old,"m")!!
        assertTrue(editing.pendingMessageRefs.isEmpty());assertTrue(editing.pendingImages.isEmpty());assertTrue(editing.pendingFileReferences.isEmpty())
        assertEquals(old.pendingMessageRefs,editing.messageEdit!!.previousMessageRefs)
        assertEquals(old.pendingImages,editing.messageEdit!!.previousImages)
        assertEquals(old.pendingFileReferences,editing.messageEdit!!.previousFileReferences)
        assertEquals(old.input,editing.messageEdit!!.previousInput)
    }
    @Test fun cancelRoleEditDiscardsOnlyEditRefsAndRestoresOriginalDraft() {
        val old=roleplay();val editing=RoleplayConversationReducer.beginEditDraft(old,"m")!!.copy(pendingMessageRefs=listOf(ref("edit-ref")),input="unsaved")
        val restored=RoleplayConversationReducer.restoreEditDraft(editing)
        assertEquals(old.input,restored.input);assertEquals(old.pendingMessageRefs,restored.pendingMessageRefs)
        assertEquals(old.pendingImages,restored.pendingImages);assertEquals(old.pendingFileReferences,restored.pendingFileReferences)
        assertEquals(old.messages,restored.messages);assertNull(restored.messageEdit)
    }
    @Test fun saveRoleEditConsumesEditRefsAndRestoresOldRefsWithoutDuplicating() {
        val old=roleplay();val editing=RoleplayConversationReducer.beginEditDraft(old,"m")!!.copy(input="edited",pendingMessageRefs=listOf(ref("edit-only")))
        val prompt=renderMessageReferences(editing.pendingMessageRefs)+editing.input
        val saved=RoleplayConversationReducer.submitEditDraft(editing,prompt)!!
        assertEquals(prompt,(saved.messages.first() as AgentMessageUi).content)
        assertFalse(prompt.contains("source-ref"));assertTrue(prompt.contains("edit-only"))
        assertEquals(old.pendingMessageRefs,saved.pendingMessageRefs);assertEquals(old.input,saved.input)
        assertFalse(renderMessageReferences(saved.pendingMessageRefs).contains("edit-only"))
        assertEquals(old.journal,saved.journal);assertEquals(old.messages.last(),saved.messages.last());assertNull(saved.messageEdit)
    }
    @Test fun failedRoleSaveDoesNotConsumeEditOrPreviousRefs() {
        val old=roleplay();val editing=RoleplayConversationReducer.beginEditDraft(old,"m")!!.copy(pendingMessageRefs=listOf(ref("edit")))
        assertNull(RoleplayConversationReducer.submitEditDraft(editing,""))
        assertEquals(listOf(ref("edit")),editing.pendingMessageRefs)
        assertEquals(old.pendingMessageRefs,editing.messageEdit!!.previousMessageRefs)
    }
    @Test fun ordinaryCancelRestoresPreviousRefsAndExistingImagesFilesConvention() {
        val old=draft();val editing=old.copy(input="ordinary edit",pendingMessageRefs=listOf(ref("edit")),messageEdit=MessageEditUiState("m",old.input,old.pendingImages,old.pendingFileReferences,true,previousMessageRefs=old.pendingMessageRefs))
        val restored=RoleplayConversationReducer.restoreEditDraft(editing)
        assertEquals(old,restored)
    }
    @Test fun directRevisionOutsideComposerDoesNotConsumeDraftOrRefs() {
        val old=roleplay();val revised=RoleplayConversationReducer.edit(old,"m","direct revised")!!
        assertEquals(old.pendingMessageRefs,revised.pendingMessageRefs);assertEquals(old.input,revised.input)
        val selected=RoleplayConversationReducer.select(revised,"m",0)!!
        assertEquals(old.pendingMessageRefs,selected.pendingMessageRefs);assertEquals("original",(selected.messages.first() as AgentMessageUi).content)
    }
    @Test fun newEditCannotStealAnAlreadyOwnedDraft() {
        val editing=RoleplayConversationReducer.beginEditDraft(roleplay(),"m")!!
        assertNull(RoleplayConversationReducer.beginEditDraft(editing,"later"))
        assertNull(RoleplayConversationReducer.submitEditDraft(draft(),"not editing"))
    }

    @Test fun consumedSkillDraftPublishesComposerClearButRejectedSendPreservesIt() {
        val owned=ticket();val s=draft()
        assertSame(s,owned.syncConsumedComposer(s))
        val consumed=s.copy(input="",pendingImages=emptyList(),pendingFileReferences=emptyList(),pendingMessageRefs=emptyList())
        assertEquals(consumed.inputSyncVersion+1,owned.syncConsumedComposer(consumed).inputSyncVersion)
        val later=s.copy(input="later draft")
        assertSame(later,owned.syncConsumedComposer(later))
    }
}
