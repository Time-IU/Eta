package io.github.mangi.eta.agent.display.dashboard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.runtime.AgentRuntimeClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.EtaTrace
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次待用户裁决的拦截：会话内审批卡与通知共用同一份，避免两个入口口径分裂。
 *
 * [token] 是唯一凭据：批准/拒绝必须带它回来，过期或不匹配一律作废，
 * 防止用户点开旧通知时误批了后面那一单。
 */
internal data class PendingApproval(
    val token: String,
    /** 来源：拦截产生（guard）还是模型主动提问（ask）。决定用户选完怎么继续。 */
    val source: String = SOURCE_GUARD,
    val risk: ConfirmRisk,
    val title: String,
    val fields: List<ConfirmField>,
    /** 静默档：只在聊天页留卡，不发通知、不振动、不抢焦点。 */
    val quiet: Boolean,
    /** 完整、带长度边界的原会话/运行/风险/动作/参数身份；绝不使用展示截断值。 */
    val subject: String = "",
    /** 同一主体被重复拦截的次数（>1 时卡上提示，不再重复通知）。 */
    val repeatCount: Int = 1,
    /** 卡片正文：说明「这一步要做什么、为什么问你」。 */
    val body: String = "",
    /** 可选项，至少两个。空表示用默认的批准/拒绝。 */
    val options: List<PromptOption> = emptyList(),
    /** 是否允许多选（当前 UI 只做单选，字段先留着给多选批次）。 */
    val allowMultiple: Boolean = false,
    /** 归属会话：新开会话不再继承上一会话的卡。 */
    val conversationKey: String = "",
    val createdAt: Long = System.currentTimeMillis(),
) {
    val riskLabel: String
        get() = when (risk) {
            ConfirmRisk.MONEY -> "资金"
            ConfirmRisk.PRIVACY -> "个人信息"
            ConfirmRisk.IRREVERSIBLE -> "不可撤销"
        }

    /** 资金类不提供「以后不问」：钱每次都该人批。 */
    val canRemember: Boolean get() = risk != ConfirmRisk.MONEY

    fun expired(now: Long = System.currentTimeMillis()): Boolean = now - createdAt > LIFETIME_MS

    companion object {
        /** 超过这个时间，用户再点就当作没看见：不批、不拒，只作废。 */
        const val LIFETIME_MS = 10 * 60 * 1000L

        const val SOURCE_GUARD = "guard"
        const val SOURCE_ASK = "ask"
    }
}

/** Exact-run authorization and a shared, ACK-based decision path for card and Activity. */
internal object AgentConfirmGuard {
    private const val CHANNEL = "eta_confirm_required"
    private const val CHANNEL_QUIET = "eta_confirm_quiet"
    private const val NOTIFICATION_ID = 8891
    private const val MAX_NOTIFY_PER_HOUR = 2
    private val notifyWindow = LongArray(2)
    private val lock = Any()
    private val capabilities = ApprovalCapabilities()
    private val claims = ApprovalDecisionClaims()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val mutablePendings = MutableStateFlow<List<PendingApproval>>(emptyList())
    val pendings: StateFlow<List<PendingApproval>> = mutablePendings.asStateFlow()
    private val mutableSubmissionStatus = MutableStateFlow<Map<String, String>>(emptyMap())
    val submissionStatus: StateFlow<Map<String, String>> = mutableSubmissionStatus.asStateFlow()
    private data class Evidence(val operation: ApprovalOperation, val observation: String)
    private data class Observation(val visible: List<String>, val id: String, val display: String, val version: Long)
    private val observations = java.util.concurrent.ConcurrentHashMap<String, Observation>()
    private val evidence = java.util.concurrent.ConcurrentHashMap<String, Evidence>()
    // A delivered answer is not automatically a usable authorization on a replacement canvas.
    private val reapprovalRequired = mutableMapOf<String, String>()
    @Volatile private var decisionSubmitter: ApprovalDecisionSubmitter? = null
    fun installDecisionSubmitter(submitter: ApprovalDecisionSubmitter?) { decisionSubmitter = submitter }
    @Volatile private var currentConversation = ""
    @Volatile private var spots: List<Spot> = emptyList()
    @Volatile private var texts: List<String> = emptyList()
    data class Spot(val label: String, val x: Int, val y: Int)
    private val guardedActions = setOf("tap", "tap_area", "long_press", "tap_smart", "tap_element", "long_press_element", "touch")

    fun init(context: Context) = synchronized(lock) {
        PendingPromptStore.init(context)
        if (mutablePendings.value.isEmpty()) {
            mutablePendings.value = PendingPromptStore.load().filter { !it.expired() }
        }
        // Persisted legacy cards have no trusted runtime evidence: retain for recovery, never authorize.
    }

    fun bindConversation(conversationKey: String) { currentConversation = conversationKey }
    val viewedConversation: String get() = currentConversation
    fun pendingByToken(token: String): PendingApproval? = mutablePendings.value.firstOrNull { it.token == token && !it.expired() }
    fun pendingFor(conversationKey: String): PendingApproval? = mutablePendings.value.firstOrNull {
        !it.expired() && it.conversationKey.isNotBlank() && it.conversationKey == conversationKey
    }
    fun pendingElsewhere(conversationKey: String): List<PendingApproval> = mutablePendings.value.filter {
        !it.expired() && it.conversationKey.isNotBlank() && it.conversationKey != conversationKey
    }
    fun visibleIn(conversationKey: String): Boolean = pendingFor(conversationKey) != null
    private fun conversationOfRun(runId: String): String =
        io.github.mangi.eta.agent.runtime.RunControl.state.value.conversationOfRun[runId].orEmpty()

    /** Old callers can detect risk, but cannot grant without an exact run/display/observation. */
    fun remember(nodes: List<Spot>, visible: List<String>, runId: String = "", observationId: String = "", displayKey: String = "", capturedAtElapsedMs: Long = 0) {
        spots = nodes.toList()
        texts = visible.toList()
        if (runId.isNotBlank() && observationId.isNotBlank() && displayKey.isNotBlank()) {
            // Capture time must come from the actual observation, not from this inspection call.
            observations[runId] = Observation(visible.toList(), observationId, displayKey, capturedAtElapsedMs)
        }
    }
    fun shouldGuard(action: String): Boolean = action in guardedActions
    fun labelAt(x: Int, y: Int): String? = spots.lastOrNull {
        kotlin.math.abs(it.x - x) <= 48 && kotlin.math.abs(it.y - y) <= 48
    }?.label

    fun inspect(
        context: Context?, target: String?, action: String, runId: String = "",
        observationId: String = "", displayKey: String = "", operationDescription: String = "",
    ): ConfirmDraft? {
        val observed = observations[runId]?.takeIf { it.id == observationId && it.display == displayKey && observationId.isNotBlank() }
        val visible = observed?.visible ?: texts
        val draft = ConfirmRiskDetector.inspect(target, visible) ?: return null
        // Snapshot ID is not part of operation identity: the retry must have a NEW ID but identical full evidence.
        val description = approvalDescription(listOf(target.orEmpty(), operationDescription) + visible)
        val operation = ApprovalOperation(conversationOfRun(runId), runId, draft.risk.name, action,
            description, gui = true, display = if (operationDescription.isNotBlank()) observed?.display.orEmpty() else "")
        return register(context, draft, operation, observed?.id.orEmpty(), observed?.version ?: 0)
    }

    fun inspectTool(context: Context?, toolName: String, args: JSONObject, runId: String = ""): ConfirmDraft? {
        val draft = ConfirmRiskDetector.inspectTool(toolName, args) ?: return null
        val operation = ApprovalOperation(conversationOfRun(runId), runId, draft.risk.name, toolName, canonicalApprovalJson(args))
        return register(context, draft, operation, "")
    }

    private fun register(context: Context?, draft: ConfirmDraft, operation: ApprovalOperation, observation: String, observationVersion: Long = 0): ConfirmDraft? = synchronized(lock) {
        if (capabilities.consume(operation, observation, observationVersion)) return@synchronized null
        val owner = operation.conversation
        if (owner.isBlank() || operation.run.isBlank()) return@synchronized draft
        val subject = approvalDescription(listOf(owner, operation.run, operation.risk, operation.action, operation.description, operation.display))
        val existing = mutablePendings.value.firstOrNull { it.conversationKey == owner && !it.expired() }
        val previous = existing?.takeUnless {
            val old = evidence[it.token]?.operation
            // A newly started run may request fresh confirmation; it does NOT inherit the old decision.
            old != null && old.run != operation.run &&
                (conversationOfRun(old.run).isBlank() || reapprovalRequired[it.token] == operation.run)
        }
        if (existing != null && previous == null) {
            evidence.remove(existing.token)
            reapprovalRequired.remove(existing.token)
            mutableSubmissionStatus.value = mutableSubmissionStatus.value + (existing.token to "原运行已结束；原会话的新运行已重新申请确认，旧裁决未提交、未授予许可。")
        }
        // Never replace a live unanswered operation with another operation.
        if (previous != null && previous.subject != subject) return@synchronized draft
        val pending = if (previous != null) previous.copy(repeatCount = previous.repeatCount + 1) else PendingApproval(
            token = "guard-" + java.util.UUID.randomUUID(), risk = draft.risk, title = draft.title,
            fields = draft.fields + ConfirmField("完整操作", operation.description), quiet = draft.risk == ConfirmRisk.PRIVACY,
            subject = subject, conversationKey = owner,
            body = "未执行。仅可批准本会话本次运行、完整相同的操作。批准后必须重新观察，不重放旧坐标。",
            options = buildList {
                add(PromptOption(PromptOptionId.APPROVE, "批准这一步", "仅一次、仅原会话原运行"))
                add(PromptOption(PromptOptionId.REJECT, "拒绝", "停止，不得换方式重试"))
                add(PromptOption(PromptOptionId.MANUAL, "我自己来", "停止操作，等待用户"))
                if (draft.risk != ConfirmRisk.MONEY) add(PromptOption(PromptOptionId.REMEMBER,
                    "本轮相同操作不再问", "仅原会话原运行、完整相同参数，最长 24 小时"))
            },
        )
        evidence[pending.token] = Evidence(operation, observation)
        mutablePendings.value = mutablePendings.value.filter { it.conversationKey != owner && !it.expired() } + pending
        PendingPromptStore.save(mutablePendings.value)
        if (previous == null) context?.let { runCatching { notify(it, pending) } }
        draft
    }

    private data class Decision(val text: String, val approve: Boolean = false, val remember: Boolean = false)

    /** Returns null intentionally: the shared submit path owns delivery, not selected-chat onSubmit. */
    fun choose(context: Context?, token: String, optionId: String, conversationId: String = ""): String? {
        submit(context, token, conversationId) { current ->
            val chosen = current.options.firstOrNull { it.id == optionId } ?: return@submit null
            if (current.source == PendingApproval.SOURCE_GUARD) {
                if (optionId == PromptOptionId.REMEMBER && !current.canRemember) return@submit null
                when (optionId) {
                    PromptOptionId.APPROVE, PromptOptionId.REMEMBER -> Decision(approvalText(current), true, optionId == PromptOptionId.REMEMBER)
                    PromptOptionId.REJECT -> Decision("用户拒绝了「${current.title}」。停止此操作，不得换方式重试。")
                    PromptOptionId.MANUAL -> Decision("用户将手动完成「${current.title}」。停止操作，说明进度并等待用户。")
                    else -> null
                }
            } else Decision("用户对「${current.title}」选择了「${chosen.label}」（${chosen.description}）。")
        }
        return null
    }

    fun chooseMany(context: Context?, token: String, optionIds: List<String>, conversationId: String = ""): String? {
        submit(context, token, conversationId) { current ->
            if (current.source != PendingApproval.SOURCE_ASK || !current.allowMultiple || optionIds.isEmpty()) return@submit null
            val selected = optionIds.distinct().map { id -> current.options.firstOrNull { it.id == id } ?: return@submit null }
            Decision("用户对「${current.title}」选择了：${selected.joinToString("、") { it.label }}。")
        }
        return null
    }
    fun chooseFreeform(context: Context?, token: String, text: String, extraIds: List<String> = emptyList(), conversationId: String = ""): String? {
        submit(context, token, conversationId) { current ->
            if (text.isBlank()) return@submit null
            val selected = extraIds.distinct().map { id -> current.options.firstOrNull { it.id == id } ?: return@submit null }
            Decision("用户对「${current.title}」补充：${text.trim()}。已选：${selected.joinToString("、") { it.label }}。" +
                if (current.source == PendingApproval.SOURCE_GUARD) "这不是批准，不得执行被拦截的操作。" else "")
        }
        return null
    }
    fun skip(context: Context?, token: String, conversationId: String = ""): String? {
        submit(context, token, conversationId) { Decision("用户跳过了「${it.title}」。未授予操作许可，危险操作必须停下。") }
        return null
    }

    /** Deprecated Boolean never reports scheduling as delivery success. Observe submissionStatus instead. */
    fun approve(context: Context?, token: String, conversationId: String = "", remember: Boolean = false): Boolean {
        choose(context, token, if (remember) PromptOptionId.REMEMBER else PromptOptionId.APPROVE, conversationId)
        return false
    }
    fun reject(context: Context?, token: String, conversationId: String = ""): Boolean {
        choose(context, token, PromptOptionId.REJECT, conversationId)
        return false
    }

    private fun status(token: String, message: String) = synchronized(lock) {
        mutableSubmissionStatus.value = mutableSubmissionStatus.value + (token to message)
        mutablePendings.value = mutablePendings.value.map {
            if (it.token == token) it.copy(body = it.body.substringBefore("\n裁决状态：") + "\n裁决状态：" + message) else it
        }
        PendingPromptStore.save(mutablePendings.value)
    }

    private fun submit(context: Context?, token: String, conversation: String, build: (PendingApproval) -> Decision?) {
        val current = pendingByToken(token) ?: return
        if (current.conversationKey.isBlank() || (conversation.isNotBlank() && conversation != current.conversationKey)) {
            status(token, "未提交：会话归属不匹配。请返回原会话核对。")
            return
        }
        val decision = build(current) ?: return
        val app = context?.applicationContext ?: run { status(token, "未提交：无可用上下文。"); return }
        if (!claims.begin(token)) return
        status(token, "正在提交，尚未批准执行。")
        scope.launch {
            var accepted = false
            try {
                val bound = evidence[token]
                if (bound == null || bound.operation.conversation.isBlank() || bound.operation.run.isBlank() ||
                    (decision.approve && (!bound.operation.scoped || (bound.operation.gui && (bound.observation.isBlank() ||
                        observations[bound.operation.run]?.version?.let { it > 0 } != true))))) {
                    status(token, "未提交：缺少原运行的完整操作证据。卡片保留，请返回原会话重新发起确认。")
                    return@launch
                }
                val request = ApprovalDecisionRequest(token, current.conversationKey, bound.operation.run, decision.text)
                val sender = decisionSubmitter
                val receipt = if (sender != null) sender.submit(request) else {
                    val client = AgentRuntimeClient(app, AndroidAgentLogger)
                    val active = client.queryActiveRuns()
                    val matching = (active as? AgentRuntimeClient.ActiveRunsQuery.Known)?.runs?.singleOrNull {
                        it.runId == bound.operation.run && it.conversationId == current.conversationKey
                    }
                    if (matching == null) {
                        status(token, "未提交：原运行已结束或连接不可用。卡片保留；请返回原会话重新发起该操作并确认，不会自动执行。")
                        return@launch
                    }
                    if (client.steerRun(bound.operation.run, decision.text) is AgentRuntimeClient.SteerOutcome.Accepted)
                        ApprovalDecisionReceipt(token, current.conversationKey, bound.operation.run) else null
                }
                val receiptMatches = receipt?.matches(request) == true
                val verifiedReceiver = if (sender == null || !receiptMatches) true else {
                    val receivingRun = requireNotNull(receipt).receivingRun
                    val knownOwner = conversationOfRun(receivingRun)
                    if (knownOwner.isNotBlank()) knownOwner == request.conversation else {
                        val query = AgentRuntimeClient(app, AndroidAgentLogger).queryActiveRuns()
                        (query as? AgentRuntimeClient.ActiveRunsQuery.Known)?.runs?.singleOrNull {
                            it.runId == receivingRun && it.conversationId == request.conversation
                        } != null
                    }
                }
                accepted = receiptMatches && verifiedReceiver
                if (!accepted) {
                    status(token, "未确认提交：没有原会话的接受 ACK。卡片保留；请先在原会话核对，避免重复提交。")
                    return@launch
                }
                // A registered repository/UI adapter may explicitly accept this user decision into a
                // new run in the SAME conversation. No grant transfers to any unspecified run.
                val receivingOperation = bound.operation.copy(run = requireNotNull(receipt).receivingRun)
                var authorizationInstalled = true
                synchronized(lock) {
                    if (mutablePendings.value.any { it.token == token }) {
                        if (decision.approve && current.source == PendingApproval.SOURCE_GUARD) {
                            val latest = observations[receivingOperation.run]
                            // main:0 is physical identity, not owner/run-derived. All other canvases
                            // must prove the receiving run still observes EXACTLY the same session.
                            val sameCanvas = !bound.operation.gui ||
                                (bound.operation.display == "main:0") ||
                                (latest != null && latest.display == bound.operation.display && latest.version > 0)
                            authorizationInstalled = sameCanvas && capabilities.grant(receivingOperation, decision.remember,
                                latest?.id ?: bound.observation, android.os.SystemClock.elapsedRealtime())
                            if (!authorizationInstalled) {
                                reapprovalRequired[token] = receivingOperation.run
                            }
                        }
                        if (authorizationInstalled) {
                            mutablePendings.value = mutablePendings.value.filter { it.token != token }
                            PendingPromptStore.save(mutablePendings.value)
                            evidence.remove(token)
                            reapprovalRequired.remove(token)
                        }
                    } else authorizationInstalled = false
                }
                if (!authorizationInstalled) {
                    status(token, "裁决消息已投递，但未授予操作许可：无法证明续轮仍在同一画布/会话，或授权证据已失效。" +
                        "请在原会话重新观察新画布并针对新目标重新确认；不得重放旧坐标，也不得声称已批准或完成。")
                    return@launch
                }
                clearNotification(app, token)
                status(token, "裁决已提交至指定会话与接收运行；未重放任何操作。")
            } catch (_: Exception) {
                status(token, "未确认提交：连接失败。卡片保留，请在原会话核对后重试。")
            } finally {
                claims.finish(token, accepted)
            }
        }
    }

    /** Recovery navigation is deliberately NOT reported as successful submission. */
    fun openOriginalConversation(context: Context, token: String): Boolean {
        val current = pendingByToken(token) ?: return false
        if (current.conversationKey.isBlank()) return false
        return runCatching {
            context.startActivity(Intent(context, io.github.mangi.eta.ui.MainActivity::class.java)
                .setAction(io.github.mangi.eta.ui.MainActivity.ACTION_OPEN_CONVERSATION_ID)
                .putExtra(io.github.mangi.eta.ui.MainActivity.EXTRA_CONVERSATION_ID, current.conversationKey)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            true
        }.getOrDefault(false)
    }

    fun ask(context: Context?, question: String, body: String, options: List<PromptOption>, allowMultiple: Boolean = false, runId: String = ""): String = synchronized(lock) {
        val owner = conversationOfRun(runId)
        if (owner.isBlank() || runId.isBlank()) return@synchronized JSONObject().put("ok", false).put("code", "UNSCOPED_PROMPT").toString()
        if (mutablePendings.value.any { it.conversationKey == owner && !it.expired() }) {
            return@synchronized JSONObject().put("ok", false).put("code", "PROMPT_PENDING").put("message", "已有卡片等待回答，请勿重复提问。").toString()
        }
        val clean = options.filter { it.label.isNotBlank() }
        if (question.isBlank() || clean.size < 2 || clean.map { it.id }.distinct().size != clean.size) {
            return@synchronized JSONObject().put("ok", false).put("code", "INVALID_ASK").toString()
        }
        val pending = PendingApproval(token = "guard-" + java.util.UUID.randomUUID(), source = PendingApproval.SOURCE_ASK,
            risk = ConfirmRisk.IRREVERSIBLE, title = question, fields = emptyList(), quiet = false,
            body = body, options = clean, allowMultiple = allowMultiple, conversationKey = owner)
        evidence[pending.token] = Evidence(ApprovalOperation(owner, runId, pending.risk.name, "ask_user", question + body), "")
        mutablePendings.value = mutablePendings.value.filter { !it.expired() } + pending
        PendingPromptStore.save(mutablePendings.value)
        context?.let { runCatching { notify(it, pending) } }
        JSONObject().put("ok", true).put("code", "AWAITING_USER").put("token", pending.token)
            .put("message", "问题已展示。不要猜测答案，也不要重复提问；本轮可以结束，等待用户回复。").toString()
    }

    private fun approvalText(current: PendingApproval): String =
        "用户仅确认「${current.title}」这一个精确操作：\n" + current.fields.joinToString("\n") { "${it.label}: ${it.value}" } +
            "\n必须重新观察，使用新观察中的目标，不得重放旧坐标或更改金额、收款方、目标。" +
            "许可仅限原会话原运行，默认仅一次。本消息不是执行结果，不得声称已支付或已完成。"

    fun dismiss(token: String) = synchronized(lock) {
        mutablePendings.value = mutablePendings.value.filter { it.token != token }
        evidence.remove(token)
        reapprovalRequired.remove(token)
        PendingPromptStore.save(mutablePendings.value)
    }
    fun clearGrants() { capabilities.clear() }
    private fun notificationIdOf(token: String): Int = NOTIFICATION_ID + (token.hashCode() and 0x7FFF)
    private fun clearNotification(context: Context?, token: String = "") {
        val app = context?.applicationContext ?: return
        runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(if (token.isBlank()) NOTIFICATION_ID else notificationIdOf(token)) }
    }
    private fun takeNotifyBudget(): Boolean = synchronized(notifyWindow) {
        val now = System.currentTimeMillis()
        if (now - notifyWindow[0] > 3_600_000L) { notifyWindow[0] = now; notifyWindow[1] = 0 }
        if (notifyWindow[1] >= MAX_NOTIFY_PER_HOUR) return@synchronized false
        notifyWindow[1] += 1
        true
    }
    fun blockedResult(draft: ConfirmDraft): String = JSONObject().put("ok", false).put("code", "NEED_CONFIRM")
        .put("risk", draft.risk.name).put("title", draft.title)
        .put("message", "危险操作已拦下，没有执行。等待用户明确裁决，不得换坐标或工具重试，不得声称已经支付。")
        .put("fields", JSONArray().apply { draft.fields.forEach { put(JSONObject().put("label", it.label).put("value", it.value)) } }).toString()

    private fun notify(context: Context, pending: PendingApproval) {
        // 静默档不打扰人，也就不必要求通知权限：没有权限时直接跳过，会话卡还在。
        if (pending.quiet) {
            AndroidAgentLogger.info("Confirm pending (quiet) risk=${pending.risk} title=${pending.title}")
            return
        }
        // 人就在 App 里：审批卡已经在会话末尾，再叠一条通知只是噪音。
        if (io.github.mangi.eta.core.AppForeground.isForeground) {
            AndroidAgentLogger.info("Confirm pending (in-app) risk=${pending.risk} title=${pending.title}")
            return
        }
        // 打扰预算只约束「拦截类」：那类可能一分钟内连拦好几次。
        // 提问类（ask_user）是模型等你拍板才发起的，一小时内本来就没几条，
        // 用预算把它静默掉等于让对话卡死——实测确实会被前面的拦截把额度吃光。
        if (pending.source != PendingApproval.SOURCE_ASK && !takeNotifyBudget()) {
            AndroidAgentLogger.info("Confirm notify budget exhausted risk=${pending.risk} title=${pending.title}")
            return
        }
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            AndroidAgentLogger.warn("Confirm notification skipped: POST_NOTIFICATIONS not granted")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.confirm_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.confirm_channel_desc)
                enableVibration(true)
            },
        )
        val token = pending.token
        // 通知正文要能看懂「在等什么」：有字段列字段，纯提问就列选项。
        val summary = if (pending.fields.isNotEmpty()) {
            pending.fields.joinToString(" · ") { "${it.label} ${it.value}" }.take(120)
        } else {
            pending.options.joinToString(" / ") { it.label }.take(120)
        }
        val title = if (pending.source == PendingApproval.SOURCE_ASK) {
            "Eta 有问题要问你"
        } else {
            context.getString(R.string.confirm_notify_title, pending.title)
        }
        // 证据图：从当前灵屏真帧裁一块，确认页展示真实画面而不是生成式卡片。
        val preview = "" // Never capture an unrelated owner display as approval evidence.
        // 提问的答案只能在会话卡上选，半屏确认页帮不上忙：直接进**那一张卡所属的会话**。
        // 带着会话 id 走 ACTION_OPEN_CONVERSATION_ID，MainActivity 会切到对应会话；
        // 用户在通知上长按/下拉进小窗，就能一边看别的应用一边把这一题答了。
        val askIntent = Intent(context, io.github.mangi.eta.ui.MainActivity::class.java)
            .setAction(io.github.mangi.eta.ui.MainActivity.ACTION_OPEN_CONVERSATION_ID)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(
                io.github.mangi.eta.ui.MainActivity.EXTRA_CONVERSATION_ID,
                pending.conversationKey,
            )
        val confirmIntent = Intent(context, AgentConfirmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val open = PendingIntent.getActivity(
            context,
            notificationIdOf(token),
            (if (pending.source == PendingApproval.SOURCE_ASK) askIntent else confirmIntent)
                // 跳转必须带上归属会话：不然只会停在上次那个会话，
                // 卡片按 conversationKey 过滤后就消失，用户看到「跳了但没东西」。
                .putExtra(io.github.mangi.eta.ui.MainActivity.EXTRA_CONVERSATION_KEY, pending.conversationKey)
                .putExtra(AgentConfirmActivity.EXTRA_CONVERSATION, pending.conversationKey)
                .putExtra(AgentConfirmActivity.EXTRA_TOKEN, token)
                .putExtra(AgentConfirmActivity.EXTRA_TITLE, pending.title)
                .putExtra(AgentConfirmActivity.EXTRA_RISK, pending.risk.name)
                .putExtra(
                    AgentConfirmActivity.EXTRA_FIELDS,
                    pending.fields.joinToString("|") { "${it.label}=${it.value}" },
                )
                .putExtra(AgentConfirmActivity.EXTRA_PREVIEW_PATH, preview),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.notify(
            notificationIdOf(token),
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(summary.ifBlank { context.getString(R.string.confirm_notify_body) })
                .setStyle(Notification.BigTextStyle().bigText(summary))
                .setCategory(Notification.CATEGORY_REMINDER)
                // heads-up（悬浮通知）：不锁屏也能直接看到，且不会把当前应用顶走。
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build(),
        )
        AndroidAgentLogger.info("Confirm required risk=${pending.risk} fields=${pending.fields.size}")
    }
}
