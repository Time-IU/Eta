package io.github.mangi.eta.agent.display

import org.junit.Assert.*
import org.junit.Test

class GuiRiskPolicyTest {
    private fun evidence(display: Int = 0, owner: String = "A", run: String = "run-A") =
        GuiRiskObservation(owner, run, "obs-A", display, 7, 1_000L, 1080, 2400, listOf(
            GuiRiskNode(0, "订单总计 ￥199.00", "", 0, 50, 1080, 120),
            GuiRiskNode(1, "立即支付", "", 400, 2000, 1000, 2200, true),
            GuiRiskNode(2, "返回", "", 0, 2000, 200, 2200, true),
        ), "shop", 18)

    @Test fun sameCoordinateOnOtherDisplayCannotReuseEvidence() {
        assertEquals("STALE_OBSERVATION", GuiRiskPolicy.validate(evidence(), "A", "run-A", 14, 7, 1100))
    }

    @Test fun ownerAndRunAreBothPartOfTheEvidenceContract() {
        assertEquals("STALE_OBSERVATION", GuiRiskPolicy.validate(evidence(), "B", "run-A", 0, 7, 1100))
        assertEquals("STALE_OBSERVATION", GuiRiskPolicy.validate(evidence(), "A", "run-B", 0, 7, 1100))
    }

    @Test fun releaseAndRelaunchInvalidateEvenRecentEvidence() {
        assertEquals("STALE_OBSERVATION", GuiRiskPolicy.validate(evidence(), "A", "run-A", 0, 8, 1100))
    }

    @Test fun staleAndFutureEvidenceBothFailClosed() {
        assertEquals("STALE_OBSERVATION", GuiRiskPolicy.validate(evidence(), "A", "run-A", 0, 7, 17000))
        assertEquals("STALE_OBSERVATION", GuiRiskPolicy.validate(evidence(), "A", "run-A", 0, 7, 999))
    }

    @Test fun missingObservationAndWrongReferenceNeverReachTargetInspection() {
        assertEquals("NO_OBSERVATION", GuiRiskPolicy.validate(null, "A", "run-A", 0, 7, 1100))
        assertEquals("STALE_OBSERVATION", GuiRiskPolicy.validate(evidence(), "A", "run-A", 0, 7, 1100, "obs-B"))
        assertNull(GuiRiskPolicy.validate(evidence(), "A", "run-A", 0, 7, 1100, "obs-A"))
    }

    @Test fun actualHitBoundsSelectPaymentRatherThanAnUnrelatedCachedCenter() {
        assertEquals("立即支付", GuiRiskPolicy.target(evidence(), 700, 2100))
        assertEquals("返回", GuiRiskPolicy.target(evidence(), 100, 2100))
        assertEquals("", GuiRiskPolicy.target(evidence(), 300, 2100))
    }

    @Test fun nodeActionUsesExactObservedNodeIdentity() {
        assertEquals("立即支付", GuiRiskPolicy.target(evidence(), 700, 2100, 1))
        assertThrows(IllegalStateException::class.java) { GuiRiskPolicy.target(evidence(), 700, 2100, 99) }
    }

    @Test fun outOfBoundsCannotBeSilentlyClampedToAPayButton() {
        assertThrows(IllegalArgumentException::class.java) { GuiRiskPolicy.target(evidence(), 1080, 2100) }
        assertThrows(IllegalArgumentException::class.java) { GuiRiskPolicy.target(evidence(), -1, 0) }
    }

    @Test fun blankContainerUsesItsOwnDescendantsNotTheRestOfTheScreen() {
        val observed = evidence().copy(nodes = evidence().nodes + listOf(
            GuiRiskNode(3, "", "", 400, 2000, 1000, 2200, true),
        ))
        assertEquals("立即支付", GuiRiskPolicy.target(observed, 700, 2100, 3))
    }

    @Test fun allFourInjectionBackendsApplyIdenticalPaymentRiskClassification() {
        for (display in listOf(0, 11, 22, 33)) { // main / old canvas / owned / raw action
            val observed = evidence(display)
            assertNull(GuiRiskPolicy.validate(observed, "A", "run-A", display, 7, 1100))
            val label = GuiRiskPolicy.target(observed, 700, 2100)
            assertTrue(GuiRiskPolicy.shouldInspect(label, observed.nodes.map { it.label }))
        }
    }

    @Test fun ordinaryRequestedSendIsNotBlanketReconfirmed() {
        for (label in listOf("发送", "Send", "搜索", "回车")) {
            assertFalse(label, GuiRiskPolicy.shouldInspect(label, listOf("聊天记录", "这是我的普通消息")))
        }
        assertTrue(GuiRiskPolicy.shouldInspect("发送银行卡验证码", listOf("聊天记录")))
    }

    @Test fun clearDestructiveAndPaymentTargetsStillNeedInspection() {
        for (label in listOf("确认支付", "删除账户", "格式化磁盘", "清空数据", "转账")) {
            assertTrue(label, GuiRiskPolicy.shouldInspect(label, emptyList()))
        }
    }

    @Test fun checkoutFallbackOnlySeesItsOwnPageEvidence() {
        assertTrue(GuiRiskPolicy.shouldInspect("", listOf("确认支付", "￥199.00")))
        assertFalse(GuiRiskPolicy.shouldInspect("", listOf("普通聊天")))
        assertFalse(GuiRiskPolicy.shouldInspect("返回", listOf("确认支付", "￥199.00")))
        assertFalse(GuiRiskPolicy.shouldInspect("发送", listOf("朋友说他付了 ￥199.00")))
    }
}
