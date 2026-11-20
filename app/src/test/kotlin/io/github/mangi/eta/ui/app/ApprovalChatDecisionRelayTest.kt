package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.display.dashboard.ApprovalDecisionRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ApprovalChatDecisionRelayTest {
    private val request = ApprovalDecisionRequest("token-A", "conversation-A", "origin-A", "user decision")
    private class Journal : ApprovalChatDecisionRelay.Journal {
        val records = mutableMapOf<String, ApprovalChatDecisionRelay.Record>()
        var failSave = false
        var rejectPhase: ApprovalChatDecisionRelay.Phase? = null
        override fun read(token: String) = records[token]
        override fun save(token: String, record: ApprovalChatDecisionRelay.Record): Boolean {
            if (failSave || rejectPhase == record.phase) return false
            records[token] = record
            return true
        }
        override fun remove(token: String): Boolean { records.remove(token); return true }
    }
    private class Transport : ApprovalChatDecisionRelay.Transport {
        var owners: List<ApprovalChatDecisionRelay.Owner>? = emptyList()
        var steerAck = ApprovalChatDecisionRelay.SteerAck.Accepted
        var starts = 0
        val steers = mutableListOf<Pair<String, String>>()
        val steerConversations = mutableListOf<String>()
        var allowStart = true
        var markDispatch = true
        var startedAck = true
        var pauseSteer = false
        var dispatchedBeforeStart = false
        override suspend fun active() = owners
        override suspend fun steer(run: String, conversation: String, text: String): ApprovalChatDecisionRelay.SteerAck {
            steers += run to text
            steerConversations += conversation
            if (pauseSteer) delay(10)
            return steerAck
        }
        override suspend fun startOriginalConversation(
            request: ApprovalDecisionRequest,
            receivingRun: String,
            beforeDispatch: () -> Boolean,
        ): Boolean {
            starts++
            if (!allowStart) return false
            if (markDispatch) {
                dispatchedBeforeStart = beforeDispatch()
                if (!dispatchedBeforeStart) return false
            }
            return startedAck
        }
    }
    private fun activeOrigin() = listOf(ApprovalChatDecisionRelay.Owner(request.originatingRun, request.conversation))
    private fun relay(j: Journal, t: Transport) = ApprovalChatDecisionRelay(j, t)

    @Test fun activeOriginalUsesExactRunAckNotAnotherConversation() = runBlocking {
        val j = Journal(); val t = Transport(); t.owners = activeOrigin() + ApprovalChatDecisionRelay.Owner("other-run", "conversation-B")
        val receipt = relay(j, t).submit(request)
        assertEquals(request.originatingRun, receipt?.receivingRun)
        assertEquals(listOf(request.originatingRun to request.text), t.steers)
        assertEquals(listOf(request.conversation), t.steerConversations)
        assertEquals(0, t.starts)
    }
    @Test fun acceptedTokenNeverSentTwice() = runBlocking {
        val j = Journal(); val t = Transport(); t.owners = activeOrigin(); val r = relay(j,t)
        assertNotNull(r.submit(request)); assertNotNull(r.submit(request))
        assertEquals(1,t.steers.size)
    }
    @Test fun definiteRejectionKeepsDecisionRetryableWithoutStartingNewRound() = runBlocking {
        val j=Journal();val t=Transport();t.owners=activeOrigin();t.steerAck=ApprovalChatDecisionRelay.SteerAck.Rejected;val r=relay(j,t)
        assertNull(r.submit(request));assertNull(j.read(request.token));assertEquals(0,t.starts)
        t.steerAck=ApprovalChatDecisionRelay.SteerAck.Accepted
        assertNotNull(r.submit(request));assertEquals(2,t.steers.size)
    }
    @Test fun uncertainSteerNeverReplaysEvenIfOriginalRunHasEnded() = runBlocking {
        val j=Journal();val t=Transport();t.owners=activeOrigin();t.steerAck=ApprovalChatDecisionRelay.SteerAck.Unknown;val r=relay(j,t)
        assertNull(r.submit(request));t.owners=emptyList();assertNull(r.submit(request))
        assertEquals(1,t.steers.size);assertEquals(0,t.starts)
        assertEquals(ApprovalChatDecisionRelay.Phase.Dispatching,j.read(request.token)?.phase)
    }
    @Test fun unavailableRuntimeDoesNotDispatch() = runBlocking {
        val j=Journal();val t=Transport();t.owners=null
        assertNull(relay(j,t).submit(request));assertEquals(0,t.starts);assertTrue(t.steers.isEmpty())
    }
    @Test fun sameRunWrongOwnerFailsClosed() = runBlocking {
        val j=Journal();val t=Transport();t.owners=listOf(ApprovalChatDecisionRelay.Owner(request.originatingRun,"B"))
        assertNull(relay(j,t).submit(request));assertEquals(0,t.starts);assertTrue(t.steers.isEmpty())
    }
    @Test fun differentRunInSameConversationIsNotAnOriginReplacement() = runBlocking {
        val j=Journal();val t=Transport();t.owners=listOf(ApprovalChatDecisionRelay.Owner("new-unrelated",request.conversation))
        assertNull(relay(j,t).submit(request));assertEquals(0,t.starts);assertTrue(t.steers.isEmpty())
    }
    @Test fun endedOriginNewRoundRequiresRealStartAck() = runBlocking {
        val j=Journal();val t=Transport();val receipt=relay(j,t).submit(request)
        assertEquals(ApprovalChatDecisionRelay.continuationRun(request),receipt?.receivingRun)
        assertEquals(request.conversation,receipt?.conversation);assertTrue(t.dispatchedBeforeStart)
        assertEquals(ApprovalChatDecisionRelay.Phase.Accepted,j.read(request.token)?.phase)
    }
    @Test fun noAckNewRoundRetainsTokenAndDoesNotResend() = runBlocking {
        val j=Journal();val t=Transport();t.startedAck=false;val r=relay(j,t)
        assertNull(r.submit(request));assertNull(r.submit(request));assertEquals(1,t.starts)
        assertEquals(ApprovalChatDecisionRelay.Phase.Dispatching,j.read(request.token)?.phase)
    }
    @Test fun timedOutStartCanRecoverOnlyExactReceivingRun() = runBlocking {
        val j=Journal();val t=Transport();t.startedAck=false;val r=relay(j,t)
        assertNull(r.submit(request));t.owners=listOf(ApprovalChatDecisionRelay.Owner(ApprovalChatDecisionRelay.continuationRun(request),request.conversation))
        assertNotNull(r.submit(request));assertEquals(1,t.starts)
    }
    @Test fun timedOutStartCannotRecoverWrongConversation() = runBlocking {
        val j=Journal();val t=Transport();t.startedAck=false;val r=relay(j,t)
        assertNull(r.submit(request));t.owners=listOf(ApprovalChatDecisionRelay.Owner(ApprovalChatDecisionRelay.continuationRun(request),"wrong"))
        assertNull(r.submit(request));assertEquals(1,t.starts)
    }
    @Test fun preflightFailureBeforeDispatchPermitsSafeRetry() = runBlocking {
        val j=Journal();val t=Transport();t.allowStart=false;val r=relay(j,t)
        assertNull(r.submit(request));assertEquals(ApprovalChatDecisionRelay.Phase.Planned,j.read(request.token)?.phase)
        t.allowStart=true;assertNotNull(r.submit(request));assertEquals(2,t.starts)
    }
    @Test fun trueCallbackWithoutDurableDispatchCannotForgeAck() = runBlocking {
        val j=Journal();val t=Transport();t.markDispatch=false
        assertNull(relay(j,t).submit(request));assertEquals(ApprovalChatDecisionRelay.Phase.Planned,j.read(request.token)?.phase)
    }
    @Test fun journalFailureBlocksNetworkSteer() = runBlocking {
        val j=Journal();j.failSave=true;val t=Transport();t.owners=activeOrigin()
        assertNull(relay(j,t).submit(request));assertTrue(t.steers.isEmpty())
    }
    @Test fun journalFailureBlocksNewRoundDispatch() = runBlocking {
        val j=Journal();j.rejectPhase=ApprovalChatDecisionRelay.Phase.Dispatching;val t=Transport()
        assertNull(relay(j,t).submit(request));assertFalse(t.dispatchedBeforeStart)
    }
    @Test fun ackRecordWriteFailureDoesNotCauseDuplicateSteer() = runBlocking {
        val j=Journal();j.rejectPhase=ApprovalChatDecisionRelay.Phase.Accepted;val t=Transport();t.owners=activeOrigin();val r=relay(j,t)
        assertNull(r.submit(request));j.rejectPhase=null;assertNull(r.submit(request));assertEquals(1,t.steers.size)
    }
    @Test fun changingTextWithSameTokenIsRejected() = runBlocking {
        val j=Journal();val t=Transport();t.owners=activeOrigin();val r=relay(j,t)
        assertNotNull(r.submit(request));assertNull(r.submit(request.copy(text="different decision")));assertEquals(1,t.steers.size)
    }
    @Test fun changingOwnerOrOriginWithSameTokenIsRejected() = runBlocking {
        val j=Journal();val t=Transport();t.owners=activeOrigin();val r=relay(j,t)
        assertNotNull(r.submit(request));assertNull(r.submit(request.copy(conversation="B")));assertNull(r.submit(request.copy(originatingRun="B")))
    }
    @Test fun recreatingRelayUsesDurableDedupRecord() = runBlocking {
        val j=Journal();val t=Transport();assertNotNull(relay(j,t).submit(request));assertNotNull(relay(j,t).submit(request));assertEquals(1,t.starts)
    }
    @Test fun concurrentCardAndActivityUseOneSubmission() = runBlocking {
        val j=Journal();val t=Transport();t.owners=activeOrigin();t.pauseSteer=true
        coroutineScope {
            val a=async { relay(j,t).submit(request) };val b=async { relay(j,t).submit(request) }
            assertNotNull(a.await());assertNotNull(b.await())
        }
        assertEquals(1,t.steers.size)
    }
    @Test fun blankEvidenceDoesNotDispatch() = runBlocking {
        val j=Journal();val t=Transport();val r=relay(j,t)
        assertNull(r.submit(request.copy(token="")));assertNull(r.submit(request.copy(conversation="")))
        assertNull(r.submit(request.copy(originatingRun="")));assertNull(r.submit(request.copy(text=" ")))
        assertEquals(0,t.starts);assertTrue(t.steers.isEmpty())
    }
    @Test fun lengthDelimitedFingerprintsDoNotCollideOnConcatenationBoundaries() {
        assertNotEquals(ApprovalChatDecisionRelay.fingerprint(request.copy(conversation="ab",originatingRun="c")),
            ApprovalChatDecisionRelay.fingerprint(request.copy(conversation="a",originatingRun="bc")))
        assertNotEquals(ApprovalChatDecisionRelay.continuationRun(request),ApprovalChatDecisionRelay.continuationRun(request.copy(text=request.text+"!")))
    }
    @Test fun preparedMetadataAloneCannotRecoverLostAcceptance() = runBlocking {
        val j=Journal();val t=Transport();t.startedAck=false;val r=relay(j,t)
        assertNull(r.submit(request))
        t.owners=listOf(ApprovalChatDecisionRelay.Owner(ApprovalChatDecisionRelay.continuationRun(request),request.conversation,accepted=false))
        assertNull(r.submit(request));assertEquals(1,t.starts)
        t.owners=listOf(ApprovalChatDecisionRelay.Owner(ApprovalChatDecisionRelay.continuationRun(request),request.conversation,accepted=true))
        assertNotNull(r.submit(request));assertEquals(1,t.starts)
    }
    @Test fun duplicateRuntimeOwnerRowsAreNotProof() = runBlocking {
        val j=Journal();val t=Transport();t.owners=activeOrigin()+activeOrigin()
        assertNull(relay(j,t).submit(request));assertTrue(t.steers.isEmpty());assertEquals(0,t.starts)
    }
    @Test fun changingDecisionAfterAnUncertainAttemptCannotSendAgain() = runBlocking {
        val j=Journal();val t=Transport();t.startedAck=false;val r=relay(j,t)
        assertNull(r.submit(request));assertNull(r.submit(request.copy(text="different")))
        assertEquals(1,t.starts)
    }

    @Test fun corruptJournalCannotRedirectToUnrelatedRun() = runBlocking {
        val j=Journal();val t=Transport()
        j.records[request.token]=ApprovalChatDecisionRelay.Record(ApprovalChatDecisionRelay.fingerprint(request),"unrelated",ApprovalChatDecisionRelay.Phase.Accepted)
        assertNull(relay(j,t).submit(request));assertEquals(0,t.starts);assertTrue(t.steers.isEmpty())
    }
    @Test fun conflictingReceivingRunOwnershipCannotRecoverAck() = runBlocking {
        val j=Journal();val t=Transport();t.startedAck=false;val r=relay(j,t)
        assertNull(r.submit(request));val target=ApprovalChatDecisionRelay.continuationRun(request)
        t.owners=listOf(ApprovalChatDecisionRelay.Owner(target,request.conversation),ApprovalChatDecisionRelay.Owner(target,"wrong"))
        assertNull(r.submit(request));assertEquals(1,t.starts)
    }

}
