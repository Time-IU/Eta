package io.github.mangi.eta.agent.display.dashboard

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** These tests execute production authorization, serialization and detector logic, not copies. */
class ApprovalCapabilitiesTest {
    private val operation = ApprovalOperation("conversation-A", "run-A", "IRREVERSIBLE", "write_file",
        "{\"path\":\"/system/etc/very-long-prefix/first\",\"content\":\"complete content\"}")

    @Test fun defaultApprovalIsExactlyOnce() {
        val ledger = ApprovalCapabilities()
        assertTrue(ledger.grant(operation, false))
        assertTrue(ledger.consume(operation))
        assertFalse(ledger.consume(operation))
    }
    @Test fun conversationIsPartOfIdentity() {
        val ledger = ApprovalCapabilities()
        ledger.grant(operation, false)
        assertFalse(ledger.consume(operation.copy(conversation = "conversation-B")))
        assertTrue(ledger.consume(operation))
    }
    @Test fun runIsPartOfIdentityEvenInSameConversation() {
        val ledger = ApprovalCapabilities()
        ledger.grant(operation, false)
        assertFalse(ledger.consume(operation.copy(run = "run-B")))
        assertTrue(ledger.consume(operation))
    }
    @Test fun noLongPrefixCollision() {
        val ledger = ApprovalCapabilities()
        ledger.grant(operation, true)
        assertFalse(ledger.consume(operation.copy(description = operation.description.replace("first", "second"))))
        assertTrue(ledger.consume(operation))
    }
    @Test fun toolAndRiskArePartOfIdentity() {
        val ledger = ApprovalCapabilities()
        ledger.grant(operation, false)
        assertFalse(ledger.consume(operation.copy(action = "edit_file")))
        assertFalse(ledger.consume(operation.copy(risk = "MONEY")))
        assertTrue(ledger.consume(operation))
    }
    @Test fun explicitRememberIsStillConversationAndRunScoped() {
        val ledger = ApprovalCapabilities()
        ledger.grant(operation, true)
        repeat(3) { assertTrue(ledger.consume(operation)) }
        assertFalse(ledger.consume(operation.copy(conversation = "B")))
        assertFalse(ledger.consume(operation.copy(run = "B")))
    }
    @Test fun expirationAndClearRevoke() {
        var now = 1L
        val ledger = ApprovalCapabilities { now }
        ledger.grant(operation, false)
        now += 600_000
        assertFalse(ledger.consume(operation))
        ledger.grant(operation, true)
        ledger.clear()
        assertFalse(ledger.consume(operation))
    }
    @Test fun blankScopeCannotAuthorize() {
        val ledger = ApprovalCapabilities()
        assertFalse(ledger.grant(operation.copy(conversation = ""), true))
        assertFalse(ledger.grant(operation.copy(run = ""), false))
    }
    @Test fun atomicConsumptionHasOneWinner() {
        val ledger = ApprovalCapabilities()
        ledger.grant(operation, false)
        val ready = CountDownLatch(16)
        val start = CountDownLatch(1)
        val winners = AtomicInteger()
        val pool = Executors.newFixedThreadPool(16)
        repeat(16) { pool.submit { ready.countDown(); start.await(); if (ledger.consume(operation)) winners.incrementAndGet() } }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, winners.get())
    }
    private val payment get() = operation.copy(risk = "MONEY", action = "tap_element", gui = true,
        display = "owned-display-session-A", description = "payee=商家甲;amount=¥28.50;button=立即支付;full order=123")
    @Test fun moneyExplicitRememberIsForcedSingleUse() {
        val ledger = ApprovalCapabilities()
        assertTrue(ledger.grant(payment, true, "old-snapshot", 10))
        assertTrue(ledger.consume(payment, "new-snapshot", 11))
        assertFalse(ledger.consume(payment, "another-snapshot", 12))
    }
    @Test fun moneyRequiresObservationAfterApprovalNotMerelyDifferentId() {
        val ledger = ApprovalCapabilities()
        ledger.grant(payment, false, "approval-snapshot", 10)
        assertFalse(ledger.consume(payment, "approval-snapshot", 10))
        assertFalse(ledger.consume(payment, "different-but-older", 9))
        assertFalse(ledger.consume(payment, "", 11))
        assertTrue(ledger.consume(payment, "observed-after-approval", 11))
    }
    @Test fun changedAmountPayeeOrDisplayCannotUsePaymentApproval() {
        val ledger = ApprovalCapabilities()
        ledger.grant(payment, false, "old", 10)
        assertFalse(ledger.consume(payment.copy(description = payment.description.replace("28.50", "280.50")), "new", 11))
        assertFalse(ledger.consume(payment.copy(description = payment.description.replace("商家甲", "商家乙")), "new", 11))
        assertFalse(ledger.consume(payment.copy(display = "another-display"), "new", 11))
        assertTrue(ledger.consume(payment, "new", 11))
    }
    @Test fun guiWithoutScopedDisplayAndSnapshotCannotAuthorize() {
        val ledger = ApprovalCapabilities()
        assertFalse(ledger.grant(payment.copy(display = ""), false, "old", 10))
        assertFalse(ledger.grant(payment, false))
        assertFalse(ledger.grant(payment, false, "old", 0))
    }
    @Test fun rememberedGuiNeedsNewObservationOnEveryUse() {
        val ledger = ApprovalCapabilities()
        val gui = payment.copy(risk = "PRIVACY")
        ledger.grant(gui, true, "old", 10)
        assertTrue(ledger.consume(gui, "new", 11))
        assertFalse(ledger.consume(gui, "new", 11))
        assertTrue(ledger.consume(gui, "newer", 12))
    }
    @Test fun completeJsonStableAcrossKeyOrdering() {
        val a = JSONObject("""{"command":"rm -rf /system/very-long-prefix/a","environment":"linux","nested":{"b":2,"a":1}}""")
        val b = JSONObject("""{"nested":{"a":1,"b":2},"environment":"linux","command":"rm -rf /system/very-long-prefix/a"}""")
        assertEquals(canonicalApprovalJson(a), canonicalApprovalJson(b))
        b.put("environment", "android")
        assertNotEquals(canonicalApprovalJson(a), canonicalApprovalJson(b))
    }
    @Test fun completeJsonDistinguishesContentBeyondOldCutoff() {
        val content = "x".repeat(400)
        val a = JSONObject().put("path", "/system/etc/same-long-prefix/file").put("content", content + "A")
        val b = JSONObject().put("path", "/system/etc/same-long-prefix/file").put("content", content + "B")
        assertNotEquals(canonicalApprovalJson(a), canonicalApprovalJson(b))
        assertTrue(canonicalApprovalJson(a).contains(content + "A"))
    }
    @Test fun framedDescriptionsCannotCollideThroughDelimiters() {
        assertNotEquals(approvalDescription(listOf("A|B", "C")), approvalDescription(listOf("A", "B|C")))
        assertTrue(approvalDescription(listOf("x".repeat(400))).contains("x".repeat(400)))
    }
    @Test fun detectorShowsCompleteCommandAndWriteContent() {
        val command = "rm -rf /system/etc/" + "very-long-prefix".repeat(12) + "/last-part"
        val terminal = ConfirmRiskDetector.inspectTool("terminal", JSONObject().put("command", command))!!
        assertEquals(command, terminal.fields.first { it.label == "命令" }.value)
        val path = "/system/etc/" + "long-path".repeat(20)
        val args = JSONObject().put("path", path).put("content", "final-content-not-prefix")
        val write = ConfirmRiskDetector.inspectTool("write_file", args)!!
        assertEquals(path, write.fields.first { it.label == "路径" }.value)
        assertTrue(write.fields.any { it.value.contains("final-content-not-prefix") })
    }
    @Test fun receiptMustMatchOriginalTokenAndConversation() {
        val request = ApprovalDecisionRequest("token-A", "conversation-A", "ended-run-A", "explicit user decision")
        assertTrue(ApprovalDecisionReceipt("token-A", "conversation-A", "continuation-run-A").matches(request))
        assertFalse(ApprovalDecisionReceipt("token-B", "conversation-A", "run").matches(request))
        assertFalse(ApprovalDecisionReceipt("token-A", "conversation-B", "run").matches(request))
        assertFalse(ApprovalDecisionReceipt("token-A", "conversation-A", "").matches(request))
    }
    @Test fun invalidOrFailedSubmissionDoesNotConsumeClaim() {
        val claims = ApprovalDecisionClaims()
        assertTrue(claims.begin("token"))
        assertFalse(claims.begin("token"))
        claims.finish("token", false)
        assertTrue(claims.begin("token"))
        claims.finish("token", true)
        assertFalse(claims.begin("token"))
        assertTrue(claims.begin("other-conversation-token"))
    }
}
