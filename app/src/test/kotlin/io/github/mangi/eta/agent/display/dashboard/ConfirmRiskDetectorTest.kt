package io.github.mangi.eta.agent.display.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmRiskDetectorTest {

    @Test
    fun moneyTapCarriesAmountAndPayee() {
        val draft = ConfirmRiskDetector.inspect(
            target = "立即支付",
            texts = listOf("订单确认", "收款方 星海便利店", "应付 ¥28.50", "立即支付"),
        )

        assertEquals(ConfirmRisk.MONEY, draft?.risk)
        assertTrue(draft!!.fields.any { it.label == "金额" && it.value.contains("28.50") })
        assertTrue(draft.fields.any { it.label == "收款方" })
    }

    @Test
    fun ordinarySmsSendDoesNotRequireSecondConfirmation() {
        val draft = ConfirmRiskDetector.inspect(
            target = "发送",
            texts = listOf("编辑短信", "晚点到家", "发送"),
        )

        assertNull(draft)
    }

    @Test
    fun verificationCodeIsPrivacy() {
        val draft = ConfirmRiskDetector.inspect(
            target = "获取验证码",
            texts = listOf("登录", "手机号 13800001111", "获取验证码"),
        )

        assertEquals(ConfirmRisk.PRIVACY, draft?.risk)
        assertTrue(draft!!.fields.any { it.label == "手机号" })
    }

    @Test
    fun harmlessTapIsNotBlocked() {
        assertNull(ConfirmRiskDetector.inspect(target = "下一页", texts = listOf("商品列表", "下一页")))
    }
    @Test fun classifiedDestructiveActionsAlwaysProduceFinalDrafts() {
        for (target in listOf("Delete account", "DELETE ACCOUNT", "恢复出厂设置", "格式化", "刪除帳號",
            "Factory reset", "Format disk", "Erase all data", "恢復出廠設定")) {
            val visible = listOf("Settings", target)
            assertEquals(target, ConfirmRisk.IRREVERSIBLE, ConfirmRiskDetector.classifyGui(target, visible))
            assertEquals(target, ConfirmRisk.IRREVERSIBLE, ConfirmRiskDetector.inspect(target, visible)?.risk)
        }
    }

    @Test fun ordinarySendAndSearchRemainSafeEvenOnSensitivePages() {
        val visible = listOf("删除", "立即支付", "¥88.00", "手机号", "Delete account")
        for (target in listOf("发送", "發送", "Send", "搜索", "搜尋", "Search", "下一页")) {
            assertNull(target, ConfirmRiskDetector.classifyGui(target, visible))
            assertNull(target, ConfirmRiskDetector.inspect(target, visible))
        }
    }

    @Test fun paymentsDoNotBecomeRememberableIrreversibleWhenAmountIsMissing() {
        for (target in listOf("Pay now", "Confirm payment", "立即支付", "轉帳", "去結算")) {
            assertEquals(target, ConfirmRisk.MONEY, ConfirmRiskDetector.inspect(target, listOf(target))?.risk)
        }
    }

    @Test fun blankCheckoutRequiresPaymentAndAmountNotJustDestructivePage() {
        assertEquals(ConfirmRisk.MONEY, ConfirmRiskDetector.inspect("", listOf("Pay now", "USD 18.50"))?.risk)
        assertNull(ConfirmRiskDetector.inspect("", listOf("Delete account", "¥18.50")))
        assertNull(ConfirmRiskDetector.inspect("", listOf("Pay now")))
    }

    @Test fun privateExportsInAllSupportedScriptsHaveOneClassification() {
        for (target in listOf("私钥", "助记词", "私鑰", "助記詞", "Show private key", "Export seed phrase")) {
            assertEquals(target, ConfirmRisk.PRIVACY, ConfirmRiskDetector.classifyGui(target, listOf(target)))
            assertEquals(target, ConfirmRisk.PRIVACY, ConfirmRiskDetector.inspect(target, listOf(target))?.risk)
        }
    }
}
