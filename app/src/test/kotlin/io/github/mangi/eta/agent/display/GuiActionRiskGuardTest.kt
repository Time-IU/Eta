package io.github.mangi.eta.agent.display

import org.junit.Assert.*
import org.junit.Test

/** Boundary tests execute the real shared guard, replacing only clock, capture and approval transport.
 * No Android device, real UI injection, payment, messaging or dashboard global state is used. */
class GuiActionRiskGuardTest {
    private fun evidence(display: Int = 0, label: String = "立即支付") = GuiRiskObservation(
        "owner-A", "run-A", "obs-A", display, 3, 1000, 1080, 2400,
        listOf(GuiRiskNode(7, label, "", 400, 1900, 1000, 2200, true),
            GuiRiskNode(8, "总额 ￥199.00", "", 0, 100, 1080, 200)), "shop", 2,
    )

    @Test fun blockedRiskNeverReachesAnyOfTheFourInjectionBackends() {
        for ((backend, display) in listOf("main" to 0, "canvas" to 17, "owned" to 28, "raw" to 39)) {
            var injected = false
            var reviews = 0
            val result = GuiActionRiskGuard.inspect(null, evidence(display), "owner-A", "run-A", display,
                3, 700, 2000, "tap", nowMs = 1100, capture = { null }, reviewer = { request ->
                    reviews++
                    assertEquals("立即支付", request.target)
                    assertEquals(display, request.observation.displayId)
                    assertEquals("owner-A", request.observation.owner)
                    assertEquals("run-A", request.observation.runId)
                    "{\"ok\":false,\"code\":\"NEED_CONFIRM\"}"
                })
            if (result == null) injected = true
            assertFalse(backend, injected)
            assertEquals(backend, 1, reviews)
        }
    }

    @Test fun approvedExactActionCanProceedOnceWithoutASecondGuardCall() {
        var reviews = 0
        var injected = 0
        val result = GuiActionRiskGuard.inspect(null, evidence(), "owner-A", "run-A", 0,
            3, 700, 2000, "tap_element", index = 7, nowMs = 1100, capture = { null }, reviewer = {
                reviews++; null // approval transport consumes its one-shot capability here
            })
        if (result == null) injected++
        assertEquals(1, reviews)
        assertEquals(1, injected)
    }

    @Test fun ordinarySendKeepsTheExistingNoExtraConfirmationExperience() {
        var inspected = false
        var injected = false
        val result = GuiActionRiskGuard.inspect(null, evidence(label = "发送"), "owner-A", "run-A", 0,
            3, 700, 2000, "tap", nowMs = 1100, capture = { null }, reviewer = {
                inspected = true; "unexpected"
            })
        if (result == null) injected = true
        assertFalse(inspected)
        assertTrue(injected)
    }

    @Test fun otherOwnersEvidenceCannotEvenReachApprovalTransport() {
        var captured = false
        var reviewed = false
        val result = GuiActionRiskGuard.inspect(null, evidence(), "owner-B", "run-B", 0,
            3, 700, 2000, "tap", nowMs = 1100, capture = { captured = true; null }, reviewer = {
                reviewed = true; null
            })
        assertNotNull(result)
        assertTrue(result!!.contains("STALE_OBSERVATION"))
        assertFalse(captured)
        assertFalse(reviewed)
    }

    @Test fun normalizedConversionResultRatherThanRawInputReachesRiskInspection() {
        var inspectedX = -1
        // 648/999 * 1080 ~= 700, 833/999 * 2400 ~= 2001: these are already screen pixels.
        val screenX = (648L * 1080 / 999).toInt()
        val screenY = (833L * 2400 / 999).toInt()
        GuiActionRiskGuard.inspect(null, evidence(), "owner-A", "run-A", 0, 3,
            screenX, screenY, "tap", nowMs = 1100, capture = { null }, reviewer = {
                inspectedX = it.x; assertEquals("立即支付", it.target); "blocked"
            })
        assertEquals(screenX, inspectedX)
    }

    @Test fun expiredObservationRequiresReobservationNotApprovalOrInjection() {
        var reviewed = false
        val result = GuiActionRiskGuard.inspect(null, evidence(), "owner-A", "run-A", 0,
            3, 700, 2000, "tap", nowMs = 18000, capture = { null }, reviewer = {
                reviewed = true; null
            })
        assertNotNull(result)
        assertFalse(reviewed)
    }
}
