package io.github.mangi.eta.agent.display

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.os.SystemClock
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.display.dashboard.AgentConfirmGuard
import io.github.mangi.eta.agent.display.dashboard.ApprovalDecisionReceipt
import io.github.mangi.eta.agent.display.dashboard.ApprovalDecisionSubmitter
import io.github.mangi.eta.agent.display.dashboard.PromptOptionId
import io.github.mangi.eta.agent.runtime.RunControl
import io.github.mangi.eta.testsupport.EtaRobolectricTestRunner
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Real GUI entry -> production detector -> real confirmation guard -> real one-shot ledger.
 * Only Android capture objects and the positive continuation ACK transport are test fixtures.
 * No injected reviewer that unconditionally blocks is used for dangerous-target assertions. */
@RunWith(EtaRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GuiActionRiskGuardFollowupTest {
    private val boundRuns = mutableListOf<String>()
    private val conversations = mutableListOf<String>()
    private val unsafe: sun.misc.Unsafe get() = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        .apply { isAccessible = true }.get(null) as sun.misc.Unsafe

    private class DecisionContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSystemService(name: String): Any? = null
    }
    private fun context(): Context = unsafe.allocateInstance(DecisionContext::class.java) as Context
    private fun bind(run: String, conversation: String) {
        boundRuns += run
        conversations += conversation
        RunControl.bindRun(run, conversation)
    }
    @After fun cleanup() {
        AgentConfirmGuard.installDecisionSubmitter(null)
        AgentConfirmGuard.clearGrants()
        conversations.distinct().forEach { conversation ->
            AgentConfirmGuard.pendings.value.filter { it.conversationKey == conversation }
                .forEach { AgentConfirmGuard.dismiss(it.token) }
        }
        boundRuns.forEach { RunControl.clearRun(it, "gui-followup-test") }
    }

    private fun node(label: String, index: Int = 7, atTarget: Boolean = true): GuiRiskNode =
        GuiRiskNode(index, label, "", if (atTarget) 400 else 0, if (atTarget) 1900 else 50,
            if (atTarget) 1000 else 1080, if (atTarget) 2200 else 150, atTarget)
    private fun observed(owner: String = "owner-A", run: String = "run-A", id: String = "obs-A",
        revision: Long = 3, target: String = "立即支付", display: Int = 0,
        session: String? = null, time: Long = SystemClock.elapsedRealtime()): GuiRiskObservation = GuiRiskObservation(
        owner, run, id, display, revision, time, 1080, 2400,
        listOf(node(target), node("总额 ￥199.00", 8, false)), "shop", 2, session,
    )

    /** Allocate real production snapshot types without creating a real AccessibilityService.
     * Rect is data-only here; no Android window, node Binder or hardware is contacted. */
    private fun snapshot(evidence: GuiRiskObservation): AgentAccessibilityService.NodeSnapshot {
        val instance = unsafe.allocateInstance(AgentAccessibilityService.NodeSnapshot::class.java)
        fun set(name: String, value: Any?) {
            val field = AgentAccessibilityService.NodeSnapshot::class.java.getDeclaredField(name)
            field.isAccessible = true
            field.set(instance, value)
        }
        set("id", evidence.id); set("displayId", evidence.displayId); set("packageName", evidence.packageName)
        set("windowId", evidence.windowId ?: 0); set("capturedAtElapsedMs", evidence.capturedAtMs)
        set("nodes", evidence.nodes.map { n ->
            val bounds = unsafe.allocateInstance(Rect::class.java) as Rect
            bounds.left = n.left; bounds.top = n.top; bounds.right = n.right; bounds.bottom = n.bottom
            AgentAccessibilityService.UiNode(n.index, n.text, n.description, "Button", evidence.packageName,
                "", bounds, n.clickable, false, false, false, false, false, true)
        })
        return instance as AgentAccessibilityService.NodeSnapshot
    }

    private fun inspect(evidence: GuiRiskObservation,
        live: GuiRiskObservation? = evidence, index: Int? = null): String? = GuiActionRiskGuard.inspect(
        null, evidence, evidence.owner, evidence.runId, evidence.displayId, evidence.routeRevision,
        700, 2000, "tap", requestedId = evidence.id, index = index,
        nowMs = evidence.capturedAtMs + 1, capture = { live?.let(::snapshot) },
    )

    @Test fun blankHitInNonemptyOldTreeBecomingDeleteIsStaleBeforeReviewOrInjection() {
        for (display in listOf(0, 17, 28, 39)) {
            val old = observed(display = display).copy(nodes = listOf(node("页标题", 1, false)))
            val live = old.copy(id = "live", nodes = old.nodes + node("删除"))
            var reviewed = false
            var injected = false
            val result = GuiActionRiskGuard.inspect(null, old, old.owner, old.runId, display, old.routeRevision,
                700, 2000, "tap", nowMs = old.capturedAtMs + 1, capture = { snapshot(live) },
                reviewer = { reviewed = true; null })
            if (result == null) injected = true
            assertFalse("changed target must not reach reviewer", reviewed)
            assertFalse("changed target must not reach injector", injected)
            assertNotNull("changed target must reject", result)
            assertEquals("STALE_CONTENT", JSONObject(result).getString("code"))
        }
    }

    @Test fun entirelyEmptyOldTreeCannotAdoptNewLiveDangerousCoordinatesEither() {
        val old = observed().copy(nodes = emptyList())
        val result = inspect(old, old.copy(nodes = listOf(node("删除"))))
        assertNotNull("empty old tree may not adopt dangerous live coordinates", result)
        assertEquals("STALE_CONTENT", JSONObject(result).getString("code"))
    }

    @Test fun liveTargetChangeToBlankAlsoInvalidatesOldCoordinates() {
        val old = observed(target = "返回")
        val result = inspect(old, old.copy(nodes = listOf(node("页标题", 1, false))))
        assertEquals("STALE_CONTENT", JSONObject(result).getString("code"))
    }

    @Test fun sameObservedNodeMustStillMatchExactLiveIdentity() {
        val old = observed(target = "删除")
        val result = inspect(old, old.copy(nodes = listOf(node("确认支付"))), index = 7)
        assertEquals("STALE_CONTENT", JSONObject(result).getString("code"))
    }

    @Test fun realFinalDetectorBlocksAllExplicitlySupportedDangerousLabels() {
        for (label in listOf("Delete account", "恢复出厂设置", "格式化", "刪除帳號", "Erase all data", "私钥")) {
            val observed = observed(target = label).copy(nodes = listOf(node(label)))
            var injected = false
            val result = inspect(observed)
            if (result == null) injected = true
            assertFalse(label, injected)
            assertEquals(label, "NEED_CONFIRM", JSONObject(result).getString("code"))
        }
    }

    @Test fun ordinarySearchAndExplicitSendRemainUnblockedThroughCompleteEntry() {
        for (label in listOf("搜索", "Search", "发送", "Send", "發送")) {
            val observed = observed(target = label).copy(nodes = listOf(node(label)))
            assertNull(label, inspect(observed))
        }
    }

    private data class Continuation(val origin: GuiRiskObservation, val receiver: GuiRiskObservation)
    private fun approveIntoAcknowledgedContinuation(): Continuation {
        val conversation = "conversation-${UUID.randomUUID()}"
        val runA = "origin-${UUID.randomUUID()}"
        val runB = "approval-${UUID.randomUUID()}"
        bind(runA, conversation)
        val initial = observed(owner = runA, run = runA, id = "obs-$runA", revision = 9)
        assertEquals("NEED_CONFIRM", JSONObject(inspect(initial)).getString("code"))
        val pending = requireNotNull(AgentConfirmGuard.pendingFor(conversation))
        RunControl.clearRun(runA, "terminal-before-user-approval")
        val positiveAck = AtomicBoolean(false)
        AgentConfirmGuard.installDecisionSubmitter(ApprovalDecisionSubmitter { request ->
            assertEquals(runA, request.originatingRun)
            assertEquals(conversation, request.conversation)
            // Deterministic test transport ACK: no run is started and no real IPC is claimed.
            bind(runB, conversation)
            positiveAck.set(true)
            ApprovalDecisionReceipt(request.token, request.conversation, runB)
        })
        AgentConfirmGuard.choose(context(), pending.token, PromptOptionId.APPROVE, conversation)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (AgentConfirmGuard.pendingByToken(pending.token) != null && System.nanoTime() < deadline) Thread.sleep(2)
        assertTrue("positive receiver ACK fixture must have been checked", positiveAck.get())
        assertNull("ACK must reach the real grant path", AgentConfirmGuard.pendingByToken(pending.token))
        SystemClock.sleep(3) // advance Robolectric clock too; actual capture must be after approval
        val nextTime = SystemClock.elapsedRealtime()
        return Continuation(initial, initial.copy(owner = runB, runId = runB, id = "obs-$runB",
            routeRevision = 0, capturedAtMs = nextTime))
    }

    @Test fun endedRunApprovalIsConsumedExactlyOnceOnSameReobservedMainScreen() {
        val state = approveIntoAcknowledgedContinuation()
        assertEquals("main:0", approvalDisplayIdentity(state.receiver.displayId, null, state.receiver.routeRevision))
        assertNull("new owner/revision must not prevent exact main-screen approval", inspect(state.receiver))
        val repeated = state.receiver.copy(id = "second-new-observation", capturedAtMs = state.receiver.capturedAtMs + 1)
        assertEquals("NEED_CONFIRM", JSONObject(inspect(repeated)).getString("code"))
    }

    @Test fun continuationCapabilityDoesNotAuthorizeChangedAmountOrWindowOrOtherRun() {
        val state = approveIntoAcknowledgedContinuation()
        val receiver = state.receiver
        val changedAmount = receiver.copy(nodes = listOf(node("立即支付"), node("总额 ￥299.00", 8, false)))
        assertEquals("NEED_CONFIRM", JSONObject(inspect(changedAmount)).getString("code"))
        assertEquals("NEED_CONFIRM", JSONObject(inspect(receiver.copy(windowId = 3))).getString("code"))
        val unrelated = "unrelated-${UUID.randomUUID()}"
        bind(unrelated, "another-${UUID.randomUUID()}")
        assertEquals("NEED_CONFIRM", JSONObject(inspect(receiver.copy(owner = unrelated, runId = unrelated))).getString("code"))
        val sameConversationWrongRun = "wrong-run-${UUID.randomUUID()}"
        bind(sameConversationWrongRun, RunControl.state.value.conversationOfRun.getValue(receiver.runId))
        assertEquals("NEED_CONFIRM", JSONObject(inspect(receiver.copy(owner = sameConversationWrongRun,
            runId = sameConversationWrongRun))).getString("code"))
        assertNull("failed mismatches cannot consume the original exact one-shot grant", inspect(receiver))
    }

    @Test fun continuationStillRejectsCaptureFromBeforeApproval() {
        val state = approveIntoAcknowledgedContinuation()
        val oldCapture = state.receiver.copy(id = state.origin.id, capturedAtMs = state.origin.capturedAtMs)
        assertEquals("NEED_CONFIRM", JSONObject(inspect(oldCapture)).getString("code"))
        assertNull("old capture rejection cannot spend the fresh-observation grant", inspect(state.receiver))
    }

    @Test fun realVirtualApprovalNeverMigratesToRecreatedSessionOrNewRevision() {
        val conversation = "virtual-conversation-${UUID.randomUUID()}"
        val run = "virtual-run-${UUID.randomUUID()}"
        bind(run, conversation)
        val initial = observed(owner = "stable-owner", run = run, id = "virtual-old", display = 21,
            session = "session-A", revision = 4)
        assertEquals("NEED_CONFIRM", JSONObject(inspect(initial)).getString("code"))
        val pending = requireNotNull(AgentConfirmGuard.pendingFor(conversation))
        AgentConfirmGuard.installDecisionSubmitter(ApprovalDecisionSubmitter { request ->
            ApprovalDecisionReceipt(request.token, request.conversation, run)
        })
        AgentConfirmGuard.choose(context(), pending.token, PromptOptionId.APPROVE, conversation)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (AgentConfirmGuard.pendingByToken(pending.token) != null && System.nanoTime() < deadline) Thread.sleep(2)
        assertNull(AgentConfirmGuard.pendingByToken(pending.token))
        SystemClock.sleep(3)
        val next = initial.copy(id = "virtual-new", capturedAtMs = SystemClock.elapsedRealtime())
        assertEquals("NEED_CONFIRM", JSONObject(inspect(next.copy(sessionId = "session-B"))).getString("code"))
        assertEquals("NEED_CONFIRM", JSONObject(inspect(next.copy(routeRevision = 5))).getString("code"))
        assertNull("only the exact retained session/revision can consume the grant", inspect(next))
    }

    @Test fun virtualDisplayIdentityDoesNotMigrateToDifferentSessionOrRevision() {
        assertEquals("main:0", approvalDisplayIdentity(0, null, 999))
        assertEquals("main:0", approvalDisplayIdentity(0, "", 0))
        assertNotEquals(approvalDisplayIdentity(17, "session-A", 9), approvalDisplayIdentity(17, "session-B", 9))
        assertNotEquals(approvalDisplayIdentity(17, "session-A", 9), approvalDisplayIdentity(17, "session-A", 10))
        assertNotEquals(approvalDisplayIdentity(17, "session-A", 9), approvalDisplayIdentity(18, "session-A", 9))
        assertNotEquals("main:0", approvalDisplayIdentity(0, "virtual-session", 9))
    }
}
