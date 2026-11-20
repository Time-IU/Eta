package io.github.mangi.eta.agent.display.dashboard

import org.json.JSONObject

/**
 * 决策拦截器：在注入前判一次「这一下会不会花钱 / 泄隐私 / 不可逆」。
 *
 * 刻意做成纯函数：输入是「即将点到的控件文本」+「当前屏可见文本」，
 * 输出是一份待确认草稿。这样悬浮窗、流体云、单元测试共用同一套判定，
 * 不会出现「浮窗拦了、通知没拦」的口径分裂。
 *
 * 风险优先级：MONEY > PRIVACY > IRREVERSIBLE。命中即拦截，不做「大概没事就放行」。
 */
internal data class ConfirmDraft(
    val risk: ConfirmRisk,
    val title: String,
    val fields: List<ConfirmField>,
)

internal object ConfirmRiskDetector {

    private val moneyPattern = Regex("""(?:¥|￥|\$|USD|RMB)\s?\d+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?\s?元""", RegexOption.IGNORE_CASE)
    private val accountPattern = Regex("""\b\d{12,19}\b""")
    private val phonePattern = Regex("""(?<!\d)1[3-9]\d{9}(?!\d)""")

    // The GUI preflight and the final guard use classifyGui(), never independent word lists.
    private val moneyWords = listOf(
        "提交订单", "提交訂單", "支付", "付款", "转账", "轉帳", "轉賬", "下单", "下單",
        "结算", "結算", "购买", "購買", "充值", "儲值", "提现", "提現",
        "pay now", "confirm payment", "transfer", "checkout", "place order", "buy now",
    )
    private val destructiveWords = listOf(
        "确认提交", "確認提交", "同意并", "同意並", "删除", "刪除", "清空", "注销", "註銷",
        "格式化", "恢复出厂", "恢復出廠", "还原出厂", "還原出廠", "重置原廠", "重設原廠",
        "解绑", "解綁", "解约", "解約", "退订", "退訂", "发布", "發布", "發佈",
        "delete", "erase", "factory reset", "format disk", "format drive", "close account",
    )
    private val privacyWords = listOf(
        "身份证", "身份證", "验证码", "驗證碼", "银行卡", "銀行卡", "收货地址", "收貨地址",
        "手机号", "手機號", "密码", "密碼", "人脸", "人臉", "定位", "私钥", "私鑰",
        "助记词", "助記詞", "password", "private key", "verification code", "seed phrase",
    )
    private fun matches(text: String, words: List<String>): Boolean =
        words.any { text.contains(it, ignoreCase = true) }

    /**
     * Shared public-to-module GUI classification. A classified risk MUST produce a draft below.
     * Ordinary send/search/navigation are not blanket risks, even if the page shows money or a
     * destructive menu elsewhere. A blank custom checkout target requires both payment and amount.
     */
    fun classifyGui(target: String?, texts: List<String>): ConfirmRisk? {
        val tapped = target.orEmpty().trim()
        if (matches(tapped, moneyWords)) return ConfirmRisk.MONEY
        if (tapped.isBlank()) {
            val page = texts.joinToString(" ")
            if (matches(page, moneyWords) && moneyPattern.containsMatchIn(page)) return ConfirmRisk.MONEY
        }
        if (matches(tapped, privacyWords) || phonePattern.containsMatchIn(tapped) ||
            accountPattern.containsMatchIn(tapped)) return ConfirmRisk.PRIVACY
        if (matches(tapped, destructiveWords)) return ConfirmRisk.IRREVERSIBLE
        return null
    }

    /** 终端类工具：命令本身就是判定依据。 */
    private val terminalTools = setOf("terminal", "terminal_job", "terminal_daemon")

    /**
     * 明确的高危形态。刻意用最短的精确子串而不是宽泛正则——
     * 宁可少拦几个变体，也不要因为「rm -rf 一个临时目录」天天烦人。
     */
    private val destructiveSnippets = listOf(
        "rm -rf /", "rm -fr /", "rm -rf ~",
        "mkfs", "dd if=", "of=/dev/block", "> /dev/block/",
        "pm clear", "cmd package clear",
        "wipe data", "wipe userdata", "fastboot flash", "fastboot erase",
        "chmod -R 777 /",
    )

    /** 写这些位置等于改系统，出错不可回滚。 */
    private val protectedWritePrefixes = listOf(
        "/system", "/vendor", "/data/adb", "/init.rc", "/default.prop",
    )

    /**
     * 非 GUI 工具的危险判定：只看工具名与参数。
     *
     * GUI 那条线由 [inspect] 处理（要屏上文本）；这里是另一条独立入口——
     * terminal 里一条 `rm -rf /data` 的破坏力不比误点「确认支付」小，
     * 而它原先完全不在拦截范围内。
     */
    fun inspectTool(toolName: String, args: JSONObject): ConfirmDraft? {
        if (toolName in terminalTools) {
            val command = args.optString("command").trim()
            if (command.isEmpty()) return null
            val hit = destructiveSnippets.firstOrNull { command.contains(it) } ?: return null
            // rm -rf /data/local/tmp/xxx 是常规清理，别拦。
            if (hit.startsWith("rm -rf /") && command.contains("/data/local/tmp")) return null
            return ConfirmDraft(
                risk = ConfirmRisk.IRREVERSIBLE,
                title = "确认执行高危命令",
                fields = listOf(ConfirmField("命令", command), ConfirmField("完整参数", canonicalApprovalJson(args))),
            )
        }
        if (toolName == "write_file" || toolName == "edit_file") {
            val path = args.optString("path").trim()
            if (path.isEmpty()) return null
            if (protectedWritePrefixes.none { path.startsWith(it) }) return null
            return ConfirmDraft(
                risk = ConfirmRisk.IRREVERSIBLE,
                title = "确认写入系统目录",
                fields = listOf(ConfirmField("路径", path), ConfirmField("完整参数", canonicalApprovalJson(args))),
            )
        }
        return null
    }

    fun inspect(target: String?, texts: List<String>): ConfirmDraft? {
        val risk = classifyGui(target, texts) ?: return null
        val tapped = target.orEmpty().trim()
        val visible = texts.map { it.trim() }.filter { it.isNotEmpty() }
        val haystack = (listOf(tapped) + visible).joinToString(" ")
        val fields = when (risk) {
            ConfirmRisk.MONEY -> {
                val amount = moneyPattern.find(haystack)?.value
                val kept = listOf("动作" to tapped) + payeeOf(visible) +
                    if (amount != null) listOf("金额" to amount) + amountLineOf(visible, amount)
                    else listOf("金额" to "页面未提供可核实金额；不得猜测")
                collect(visible, kept)
            }
            ConfirmRisk.PRIVACY -> collect(visible, listOf("动作" to tapped) + privacyOf(visible) + phoneOrAccountOf(haystack))
            ConfirmRisk.IRREVERSIBLE -> collect(visible, listOf("动作" to tapped))
        }
        return ConfirmDraft(risk, when (risk) {
            ConfirmRisk.MONEY -> "确认支付"
            ConfirmRisk.PRIVACY -> "确认提交个人信息"
            ConfirmRisk.IRREVERSIBLE -> "确认不可撤销操作"
        }, fields)
    }

    /** 摘要最多四条；每个字段不截断。授权另绑定完整页面和操作参数。 */
    private fun collect(visible: List<String>, keep: List<Pair<String, String>>): List<ConfirmField> {
        val fields = LinkedHashMap<String, String>()
        keep.forEach { (label, value) ->
            if (value.isNotBlank()) fields.putIfAbsent(label, value)
        }
        if (fields.size < 4) {
            visible.forEach { line ->
                if (fields.size >= 4) return@forEach
                val trimmed = line
                if (trimmed.length >= 2) fields.putIfAbsent("屏上", trimmed)
            }
        }
        return fields.entries.map { ConfirmField(it.key, it.value) }.take(4)
    }

    private fun payeeOf(visible: List<String>): List<Pair<String, String>> =
        visible.firstOrNull { it.contains("收款") || it.contains("商家") || it.contains("店铺") }
            ?.let { listOf("收款方" to it) }
            .orEmpty()

    private fun amountLineOf(visible: List<String>, amount: String): List<Pair<String, String>> =
        visible.firstOrNull { it.contains(amount) && it != amount }
            ?.let { listOf("订单" to it) }
            .orEmpty()

    private fun privacyOf(visible: List<String>): List<Pair<String, String>> =
        visible.mapNotNull { line ->
            when {
                phonePattern.containsMatchIn(line) -> "手机号" to (phonePattern.find(line)?.value ?: line)
                accountPattern.containsMatchIn(line) -> "账号" to (accountPattern.find(line)?.value ?: line)
                else -> null
            }
        }.distinctBy { it.first }.take(2)

    private fun phoneOrAccountOf(haystack: String): List<Pair<String, String>> {
        phonePattern.find(haystack)?.let { return listOf("手机号" to it.value) }
        accountPattern.find(haystack)?.let { return listOf("账号" to it.value) }
        return emptyList()
    }
}
