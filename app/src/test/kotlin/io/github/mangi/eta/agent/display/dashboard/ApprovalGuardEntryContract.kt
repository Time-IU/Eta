package io.github.mangi.eta.agent.display.dashboard

import android.content.Context
import android.os.SystemClock
import io.github.mangi.eta.agent.display.*
import io.github.mangi.eta.agent.runtime.RunControl
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Shared production-entry tests: default GUI reviewer + Guard + RunControl + capability ledger.
 * Transport returns an explicit test receipt; no real tool injection or payment is performed. */
abstract class ApprovalGuardEntryContract {
    private lateinit var conversation: String
    private lateinit var origin: String
    private lateinit var receiving: String
    protected abstract val context: Context
    protected abstract fun advanceObservationClock()
    private val deliveries = AtomicInteger()

    @Before fun setup() {
        conversation = "conversation-" + UUID.randomUUID()
        origin = "origin-" + UUID.randomUUID()
        receiving = "receiving-" + UUID.randomUUID()
        RunControl.bindRun(origin, conversation)
        AgentConfirmGuard.clearGrants()
        AgentConfirmGuard.pendings.value.toList().forEach { AgentConfirmGuard.dismiss(it.token) }
        AgentConfirmGuard.installDecisionSubmitter(null)
    }
    @After fun cleanup() {
        AgentConfirmGuard.installDecisionSubmitter(null)
        AgentConfirmGuard.pendings.value.toList().forEach { AgentConfirmGuard.dismiss(it.token) }
        AgentConfirmGuard.clearGrants()
        RunControl.state.value.conversationOfRun.keys.toList().forEach { RunControl.clearRun(it,"test cleanup") }
    }

    private fun screen(run: String = origin, target: String = "Pay now", amount: String = "USD 28.50",
        window: Int = 2, display: Int = 0, session: String? = null, revision: Long = 3,
        observation: String = UUID.randomUUID().toString()): GuiRiskObservation = GuiRiskObservation(
        owner = run, runId = run, id = observation, displayId = display, routeRevision = revision,
        capturedAtMs = SystemClock.elapsedRealtime(), width = 1080, height = 2400,
        nodes = listOf(GuiRiskNode(7,target,"",400,1900,1000,2200,true),
            GuiRiskNode(8,amount,"",0,100,1080,200),GuiRiskNode(9,"收款方 Merchant A","",0,250,1080,300)),
        packageName = "shop.package", windowId = window, sessionId = session,
    )
    private fun inspect(evidence: GuiRiskObservation): String? = GuiActionRiskGuard.inspect(
        null, evidence, evidence.owner, evidence.runId, evidence.displayId, evidence.routeRevision,
        700,2000,"tap_element",requestedId = evidence.id,index = 7,
        nowMs = SystemClock.elapsedRealtime(),capture = { null },
    ) // real production reviewer, not a callback returning synthetic NEED_CONFIRM
    private fun block(evidence: GuiRiskObservation): PendingApproval {
        val result = inspect(evidence)
        assertNotNull("Expected real Guard rejection for ${evidence.nodes.first().text}",result)
        assertEquals("NEED_CONFIRM",JSONObject(result!!).getString("code"))
        return AgentConfirmGuard.pendingFor(conversation) ?: error("Guard failed to retain pending")
    }
    private fun installReceiver(targetRun: String = receiving, beforeReceipt: () -> Unit = {}) {
        AgentConfirmGuard.installDecisionSubmitter { request ->
            deliveries.incrementAndGet()
            assertEquals(conversation,request.conversation)
            RunControl.bindRun(targetRun,conversation)
            beforeReceipt()
            ApprovalDecisionReceipt(request.token,request.conversation,targetRun)
        }
    }
    private fun decide(pending: PendingApproval, option: String = PromptOptionId.APPROVE): String {
        AgentConfirmGuard.choose(context,pending.token,option,conversation)
        val until = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < until) {
            val status = AgentConfirmGuard.submissionStatus.value[pending.token].orEmpty()
            if (status.isNotEmpty() && !status.startsWith("正在提交")) return status
            Thread.sleep(2)
        }
        error("No decision status: ${AgentConfirmGuard.submissionStatus.value[pending.token]}")
    }
    private fun approveEndedMain(): PendingApproval {
        val pending = block(screen())
        RunControl.clearRun(origin,"original run ended")
        installReceiver()
        assertTrue(decide(pending).contains("裁决已提交"))
        assertNull(AgentConfirmGuard.pendingByToken(pending.token))
        advanceObservationClock()
        return pending
    }

    @Test fun realCompleteEntryBlocksSupportedDangerousLabels() {
        for (target in listOf("Delete account","恢复出厂设置","格式化","刪除帳號","Factory reset","Erase all data")) {
            val pending = block(screen(target = target,amount = ""))
            assertEquals(target,ConfirmRisk.IRREVERSIBLE,pending.risk)
            AgentConfirmGuard.dismiss(pending.token)
        }
    }
    @Test fun ordinarySendAndSearchDoNotGenerateApprovalCards() {
        for (target in listOf("发送","發送","Send","搜索","搜尋","Search")) assertNull(target,inspect(screen(target=target)))
        assertNull(AgentConfirmGuard.pendingFor(conversation))
    }
    @Test fun originalTerminalThenReceivingMainNewObservationConsumesExactlyOnce() {
        approveEndedMain()
        assertNull(inspect(screen(run=receiving,revision=700)))
        assertEquals("NEED_CONFIRM",JSONObject(inspect(screen(run=receiving,revision=701))!!).getString("code"))
        assertEquals(1,deliveries.get())
    }
    @Test fun mainContinuationDoesNotAuthorizeOtherRunOrConversation() {
        approveEndedMain()
        val third="unapproved-run-"+UUID.randomUUID()
        RunControl.bindRun(third,conversation)
        assertNotNull(inspect(screen(run=third)))
        val other="other-conversation-run-"+UUID.randomUUID()
        RunControl.bindRun(other,"other-conversation")
        assertNotNull(inspect(screen(run=other)))
        assertNull(inspect(screen(run=receiving)))
    }
    @Test fun mainContinuationStillRejectsDifferentAmountWindowAndPayee() {
        approveEndedMain()
        assertNotNull(inspect(screen(run=receiving,amount="USD 280.50")))
        assertNotNull(inspect(screen(run=receiving,window=99)))
        val changed=screen(run=receiving).let { it.copy(nodes=it.nodes.map { n -> if(n.index==9)n.copy(text="收款方 Merchant B") else n }) }
        assertNotNull(inspect(changed))
        assertNull(inspect(screen(run=receiving)))
    }
    @Test fun mainContinuationRejectsOldCaptureEvenWithDifferentObservationId() {
        val oldReceiving=screen(run=receiving)
        approveEndedMain()
        assertNotNull(inspect(oldReceiving.copy(id="different-but-captured-before-approval")))
        assertNull(inspect(screen(run=receiving)))
    }
    @Test fun nonMainWithoutProofRetainsPendingAndReportsReconfirmationNotSuccess() {
        val pending=block(screen(display=17,session="old-session"))
        RunControl.clearRun(origin,"ended")
        installReceiver()
        val status=decide(pending)
        assertTrue(status,status.contains("未授予操作许可"))
        assertTrue(status,status.contains("重新确认"))
        assertFalse(status,status.startsWith("裁决已提交"))
        assertNotNull(AgentConfirmGuard.pendingByToken(pending.token))
        assertEquals(1,deliveries.get())
        AgentConfirmGuard.choose(context,pending.token,PromptOptionId.APPROVE,conversation)
        Thread.sleep(10)
        assertEquals("delivered answer must not dispatch twice",1,deliveries.get())
        val replacement=block(screen(run=receiving,display=18,session="new-session"))
        assertNotEquals(pending.token,replacement.token)
        assertNotNull("old coordinates never gain a grant",inspect(screen(run=receiving,display=17,session="old-session")))
    }
    @Test fun changedVirtualSessionCannotReceiveOldGrantEvenWithSameDisplayNumber() {
        val pending=block(screen(display=17,session="old-session"))
        RunControl.clearRun(origin,"ended")
        installReceiver {
            val latest=screen(run=receiving,display=17,session="new-session")
            AgentConfirmGuard.remember(emptyList(),latest.nodes.map { it.label },receiving,latest.id,
                approvalDisplayIdentity(latest.displayId,latest.sessionId,latest.routeRevision),latest.capturedAtMs)
        }
        assertTrue(decide(pending).contains("未授予操作许可"))
        advanceObservationClock()
        block(screen(run=receiving,display=17,session="new-session"))
    }
    @Test fun provablySameRetainedVirtualSessionCanAuthorizeOnlyExactReceivingRun() {
        val pending=block(screen(display=17,session="retained-session"))
        RunControl.clearRun(origin,"ended")
        installReceiver {
            val latest=screen(run=receiving,display=17,session="retained-session")
            AgentConfirmGuard.remember(emptyList(),latest.nodes.map { it.label },receiving,latest.id,
                approvalDisplayIdentity(latest.displayId,latest.sessionId,latest.routeRevision),latest.capturedAtMs)
        }
        assertTrue(decide(pending).contains("裁决已提交"))
        advanceObservationClock()
        assertNull(inspect(screen(run=receiving,display=17,session="retained-session")))
        assertNotNull(inspect(screen(run=receiving,display=17,session="retained-session")))
    }
    @Test fun newVirtualCanvasCanBeExplicitlyReconfirmedWithoutInheritingOldPermission() {
        val old=block(screen(display=17,session="old-session"))
        RunControl.clearRun(origin,"ended")
        installReceiver()
        assertTrue(decide(old).contains("未授予操作许可"))
        val fresh=block(screen(run=receiving,display=18,session="new-session"))
        installReceiver(receiving)
        assertTrue(decide(fresh).contains("裁决已提交"))
        advanceObservationClock()
        assertNull(inspect(screen(run=receiving,display=18,session="new-session")))
        assertNotNull(inspect(screen(run=receiving,display=18,session="new-session")))
    }
    @Test fun moneyRememberCannotPassFullEntryOrInstallGrant() {
        val pending=block(screen())
        installReceiver(origin)
        AgentConfirmGuard.choose(context,pending.token,PromptOptionId.REMEMBER,conversation)
        Thread.sleep(10)
        assertEquals(0,deliveries.get())
        assertNotNull(AgentConfirmGuard.pendingByToken(pending.token))
        assertNotNull(inspect(screen()))
    }
    @Test fun wrongConversationReceiptNeverGrantsOrConsumesPending() {
        val pending=block(screen())
        AgentConfirmGuard.installDecisionSubmitter { r -> ApprovalDecisionReceipt(r.token,"wrong-conversation",receiving) }
        assertTrue(decide(pending).contains("未确认提交"))
        assertNotNull(AgentConfirmGuard.pendingByToken(pending.token))
        assertNotNull(inspect(screen()))
    }
    @Test fun resolvingOneApprovalDoesNotClearAnotherUsersPause() {
        val other="paused-other-"+UUID.randomUUID()
        RunControl.bindRun(other,"conversation-other")
        RunControl.setPaused(other,true,"user")
        val pending=block(screen())
        installReceiver(origin)
        assertTrue(decide(pending).contains("裁决已提交"))
        assertTrue(RunControl.state.value.isPaused(other))
    }
}
