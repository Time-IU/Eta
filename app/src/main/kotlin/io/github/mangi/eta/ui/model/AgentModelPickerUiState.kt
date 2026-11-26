package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.agent.subagent.SubagentActivitySnapshot
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderAuthModes
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.provider.ProviderSourceRegistry
import io.github.mangi.eta.data.provider.isLocalEndpoint
import java.text.NumberFormat
import java.util.Locale

@Immutable
internal data class AgentModelPickerUiState(
    val providerGroups: List<AgentModelProviderGroupUi> = emptyList(),
    val selectedModel: AgentModelOptionUi? = null,
    val isChanging: Boolean = false,
    /**
     * 是否已经真正投影过一次（providers 从数据库读出来的结果）。
     *
     * 初值是空 state，冷启动时 provider 表还没读出来，此时「没有模型」和「还没加载完」
     * 长得一模一样。不加这个标志，首帧会闪一条「还没有可用的模型」，DB 慢时能闪一秒。
     */
    val loaded: Boolean = false,
) {
    /**
     * 当前能不能真的把消息发出去。
     *
     * [selectedModel] 只说明「选了一个模型」，不保证它背后的 provider 填了 Key——
     * 投影时 selectedModel 会在所有 enabled provider 里按 id 兜底查找；
     * 只有 [providerGroups] 过滤掉了「Key 为空 / 未授权」的 provider。
     * 两者同时成立才算可用，缺一都会在发送时失败。
     */
    val hasUsableModel: Boolean
        get() = loaded && selectedModel != null && providerGroups.any { group -> group.models.any { it.id == selectedModel.id } }
}

@Immutable
internal data class AgentModelProviderGroupUi(
    val providerId: String,
    val providerName: String,
    val providerSourceType: String,
    val models: List<AgentModelOptionUi>,
)

@Immutable
internal data class AgentModelOptionUi(
    val id: String,
    val providerId: String,
    val providerName: String,
    val providerSourceType: String,
    val modelId: String,
    val displayName: String,
    val contextWindow: Int?,
)

@Immutable
internal data class SessionStatsSummaryUi(
    val turns: Int = 0,
    val steps: Int = 0,
    val llmMs: Long = 0L,
    val toolMs: Long = 0L,
    val ttftMs: Long = 0L,
    val ttftSteps: Int = 0,
    val decodeMs: Long = 0L,
    val decodeTokens: Int = 0,
    val promptTokens: Int = 0,
    val uncachedInputTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val hasCacheWriteTokens: Boolean = false,
    val cachedTokens: Int = 0,
    val outputTokens: Int = 0,
    val reasoningTokens: Int = 0,
    val compactionCount: Int = 0,
    val compactionPromptTokens: Int = 0,
    val compactionUncachedTokens: Int = 0,
    val compactionCacheWriteTokens: Int = 0,
    val compactionCachedTokens: Int = 0,
    val compactionOutputTokens: Int = 0,
    val lastCompactionPromptTokens: Int? = null,
    val lastCompactionCachedTokens: Int? = null,
    val lastCompactionCacheWriteTokens: Int? = null,
    val lastCompactionOutputTokens: Int? = null,
    val lastCompactionArchiveId: String = "",
    val lastCompactionPaybackTurns: Int? = null,
    val lastCompactionTurnsAfter: Int = 0,
    val lastCompactionPaidBack: Boolean = false,
    val subagentCount: Int = 0,
    val subagentPromptTokens: Int = 0,
    val subagentCachedTokens: Int = 0,
    val subagentOutputTokens: Int = 0,
    val ttlOrPrefixMissTokens: Int = 0,
    val compactionRepayTokens: Int = 0,
    val hasTokenUsage: Boolean = false,
) {
    val totalTokens: Int
        get() = promptTokens +
            outputTokens +
            compactionPromptTokens +
            compactionOutputTokens +
            subagentPromptTokens +
            subagentOutputTokens

    val hasPerformanceStats: Boolean
        get() = llmMs > 0L || toolMs > 0L || ttftSteps > 0 || (decodeMs > 0L && decodeTokens > 0)
}

@Immutable
internal data class AgentContextUsageUi(
    val contextTokens: Int?,
    val contextWindow: Int?,
    val sessionStats: SessionStatsSummaryUi = SessionStatsSummaryUi(),
    /** 最近一次响应的平均输出速度（tok/s）；没有数据时为 null。 */
    val averageTokensPerSecond: Double? = null,
    /** 最近可用的非空回答用量记录，可为运行内多请求累计；独立于上下文快照及压缩边界。 */
    val latestUsage: TokenUsageUi? = null,
    /** 上下文构成估算：系统提示词 / 工具定义 / 对话消息（元信息不是实测值）。 */
    val systemPromptTokens: Int? = null,
    val toolDefinitionTokens: Int? = null,
    val conversationTokens: Int? = null,
) {
    val progress: Float?
        get() = contextUsageProgress(contextTokens, contextWindow)

    /** 头部显示的整数百分比；没有上下文窗口或用量时为 null。 */
    val usedPercent: Int?
        get() = progress?.let { kotlin.math.round(it * 100.0).toInt() }

    /** 三段构成合计；任一为空按 0 计，全空时为 null（面板不画分段条）。 */
    val compositionTotal: Int?
        get() {
            val parts = listOfNotNull(systemPromptTokens, toolDefinitionTokens, conversationTokens)
            return if (parts.isEmpty()) null else parts.sum()
        }
}

internal object AgentModelPickerProjector {
    fun project(
        providers: List<ProviderSetting>,
        selectedProviderId: String?,
        selectedModelId: String?,
        /** 借用厂商助手（小布）是否参与模型列表；由设置里的开关决定。 */
        borrowVendorEnabled: Boolean = false,
    ): AgentModelPickerUiState {
        val enabledProviders = providers
            .asSequence()
            .filter(ProviderSetting::isEnabled)
            .filter {
                it.authMode == ProviderAuthModes.API_KEY ||
                    ProviderAuthModes.isOAuth(it.authMode) ||
                    (borrowVendorEnabled && ProviderAuthModes.isBorrowedVendor(it.authMode))
            }
            .sortedBy(ProviderSetting::sortOrder)
            .toList()
        val selectedProvider = enabledProviders.firstOrNull { it.id == selectedProviderId }
        val selectedModel = selectedProvider
            ?.models
            ?.firstOrNull { it.id == selectedModelId && it.isEnabled }
            ?.let { model -> selectedProvider.toOption(model) }
            ?: enabledProviders.asSequence()
                .flatMap { provider ->
                    provider.models.asSequence()
                        .filter { it.isEnabled }
                        .map { model -> provider.toOption(model) }
                }
                .firstOrNull { it.id == selectedModelId }
        val groups = enabledProviders
            .asSequence()
            .filter {
                // 本地/局域网服务不校验 Key，也必须进面板；否则「本地地址代理」永远选不到。
                ProviderAuthModes.isOAuth(it.authMode) ||
                    ProviderAuthModes.isBorrowedVendor(it.authMode) ||
                    (it.authMode == ProviderAuthModes.API_KEY &&
                        (it.apiKey.isNotBlank() || it.baseUrl.isLocalEndpoint()))
            }
            .mapNotNull { provider ->
                val sourceType = ProviderSourceRegistry.resolve(provider)
                val models = provider.models
                    .asSequence()
                    .filter { it.isEnabled }
                    .sortedBy { it.sortOrder }
                    .map { model -> provider.toOption(model) }
                    .toList()
                models.takeIf(List<*>::isNotEmpty)?.let {
                    AgentModelProviderGroupUi(
                        providerId = provider.id,
                        providerName = provider.name,
                        providerSourceType = sourceType,
                        models = models,
                    )
                }
            }
            .toList()
        return AgentModelPickerUiState(
            providerGroups = groups,
            selectedModel = selectedModel,
            loaded = true,
        )
    }

    private fun ProviderSetting.toOption(model: Model): AgentModelOptionUi =
        AgentModelOptionUi(
            id = model.id,
            providerId = id,
            providerName = name,
            providerSourceType = ProviderSourceRegistry.resolve(this),
            modelId = model.modelId,
            displayName = model.displayName.ifBlank { model.modelId },
            contextWindow = model.effectiveContextWindow,
        )
}

internal val AgentModelPickerUiState.modelCount: Int
    get() = providerGroups.sumOf { it.models.size }

/** 服务商名称也参与匹配：搜索服务商时列出它的全部模型。 */
internal fun AgentModelPickerUiState.filterGroups(query: String): List<AgentModelProviderGroupUi> {
    val search = ModelSearchQuery(query)
    if (search.isEmpty) return providerGroups
    return providerGroups.mapNotNull { group ->
        val models = group.models.filter { model ->
            search.matches(model.displayName, model.modelId, group.providerName)
        }
        models.takeIf(List<*>::isNotEmpty)?.let { group.copy(models = it) }
    }
}

internal fun latestContextUsage(
    messages: List<AgentChatMessageUi>,
    selectedModel: AgentModelOptionUi?,
): AgentContextUsageUi {
    // These have different lifetimes: a partial newer run must not inherit the older
    // run's request metrics, while its missing context can still use an older snapshot.
    var latestUsage: TokenUsageUi? = null
    var contextSnapshot: TokenUsageUi? = null
    var contextResolved = false
    for (message in messages.asReversed()) {
        when (message) {
            is AgentMessageUi -> {
                val recorded = message.usage
                if (latestUsage == null && recorded != null && !recorded.isEmpty) {
                    latestUsage = recorded
                }
                if (!contextResolved && recorded?.contextTokens != null) {
                    contextSnapshot = recorded
                    contextResolved = true
                }
            }
            is SystemNoticeMessageUi -> if (!contextResolved &&
                message.code == SystemNoticeCode.ContextCompaction) {
                // Preserve the existing snapshot invalidation boundary, not a latest-run boundary.
                contextResolved = true
            }
            else -> Unit
        }
        if (contextResolved && latestUsage != null) break
    }
    return AgentContextUsageUi(
        contextTokens = contextSnapshot?.contextTokens,
        contextWindow = selectedModel?.contextWindow,
        sessionStats = deriveSessionStatsSummary(messages),
        latestUsage = latestUsage,
        systemPromptTokens = contextSnapshot?.systemPromptTokens,
        toolDefinitionTokens = contextSnapshot?.toolDefinitionTokens,
        conversationTokens = contextSnapshot?.conversationTokens,
    )
}

internal fun deriveSessionStatsSummary(messages: List<AgentChatMessageUi>): SessionStatsSummaryUi {
    var turns = 0
    var steps = 0
    var llmMs = 0L
    var toolMs = 0L
    var ttftMs = 0L
    var ttftSteps = 0
    var decodeMs = 0L
    var decodeTokens = 0
    var promptTokens = 0
    var uncachedInputTokens = 0
    var cacheWriteTokens = 0
    var hasCacheWriteTokens = false
    var cachedTokens = 0
    var outputTokens = 0
    var reasoningTokens = 0
    var compactionCount = 0
    var compactionPromptTokens = 0
    var compactionUncachedTokens = 0
    var compactionCacheWriteTokens = 0
    var compactionCachedTokens = 0
    var compactionOutputTokens = 0
    var lastCompactionPromptTokens: Int? = null
    var lastCompactionCachedTokens: Int? = null
    var lastCompactionCacheWriteTokens: Int? = null
    var lastCompactionOutputTokens: Int? = null
    var lastCompactionArchiveId = ""
    var subagentCount = 0
    var subagentPromptTokens = 0
    var subagentCachedTokens = 0
    var subagentOutputTokens = 0
    var hasTokenUsage = false

    messages.forEach { message ->
        when (message) {
            is AgentMessageUi -> {
                val usage = message.usage ?: return@forEach
                val turnSteps = (usage.stepCount ?: 1).coerceAtLeast(1)
                turns += 1
                steps += turnSteps
                usage.llmMs?.takeIf { it > 0L }?.let { llmMs += it }
                usage.toolMs?.takeIf { it > 0L }?.let { toolMs += it }
                val recordedTtftSteps = usage.ttftSteps ?: if (usage.ttftMs != null) 1 else 0
                if (recordedTtftSteps > 0 && usage.ttftMs != null && usage.ttftMs > 0L) {
                    ttftMs += usage.ttftMs
                    ttftSteps += recordedTtftSteps
                }
                val turnDecodeTokens = usage.decodeTokens ?: usage.outputTokens
                if (usage.decodeMs != null && usage.decodeMs > 0L && turnDecodeTokens != null && turnDecodeTokens > 0) {
                    decodeMs += usage.decodeMs
                    decodeTokens += turnDecodeTokens
                }
                val rawOut = usage.outputTokens ?: 0
                val rawReasoning = usage.reasoningTokens ?: 0
                if (usage.inputTokens != null ||
                    usage.cachedTokens != null ||
                    usage.cacheWriteTokens != null ||
                    usage.outputTokens != null ||
                    usage.contextTokens != null
                ) {
                    hasTokenUsage = true
                    val breakdown = CacheUsageDiagnostics.resolvePromptBreakdown(usage)
                    if (breakdown.hasWriteTokens) hasCacheWriteTokens = true
                    promptTokens += breakdown.promptTokens
                    uncachedInputTokens += breakdown.uncachedInputTokens
                    cacheWriteTokens += breakdown.cacheWriteTokens
                    cachedTokens += breakdown.cacheReadTokens
                    outputTokens += rawOut
                    reasoningTokens += rawReasoning
                }
            }
            is SystemNoticeMessageUi -> {
                if (message.code == SystemNoticeCode.ContextCompaction && !message.running) {
                    val usage = message.usage ?: return@forEach
                    val breakdown = CacheUsageDiagnostics.resolvePromptBreakdown(usage)
                    val out = usage.outputTokens ?: 0
                    if (breakdown.promptTokens > 0 || out > 0 || breakdown.cacheReadTokens > 0) {
                        hasTokenUsage = true
                        compactionCount += 1
                        compactionPromptTokens += breakdown.promptTokens
                        compactionUncachedTokens += breakdown.uncachedInputTokens
                        compactionCacheWriteTokens += breakdown.cacheWriteTokens
                        compactionCachedTokens += breakdown.cacheReadTokens
                        compactionOutputTokens += out
                        lastCompactionPromptTokens = breakdown.promptTokens
                        lastCompactionCachedTokens = breakdown.cacheReadTokens
                        lastCompactionCacheWriteTokens = breakdown.cacheWriteTokens
                        lastCompactionOutputTokens = out
                        lastCompactionArchiveId = message.archiveId
                        usage.llmMs?.takeIf { it > 0L }?.let { llmMs += it }
                    }
                }
            }
            is ToolActivityMessageUi -> {
                if (message.toolName == "subagent" && !message.command.isNullOrBlank()) {
                    val snapshot = SubagentActivitySnapshot.decode(message.command)
                    snapshot?.usage?.let { subUsage ->
                        if (subUsage.inputTokens > 0 || subUsage.outputTokens > 0 || subUsage.cachedTokens > 0) {
                            hasTokenUsage = true
                            subagentCount += 1
                            subagentPromptTokens += subUsage.inputTokens
                            subagentCachedTokens += subUsage.cachedTokens
                            subagentOutputTokens += subUsage.outputTokens
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    val diagnostics = CacheUsageDiagnostics.analyze(messages)
    val payback = diagnostics.lastCompactionPayback

    return SessionStatsSummaryUi(
        turns = turns,
        steps = steps,
        llmMs = llmMs,
        toolMs = toolMs,
        ttftMs = ttftMs,
        ttftSteps = ttftSteps,
        decodeMs = decodeMs,
        decodeTokens = decodeTokens,
        promptTokens = promptTokens,
        uncachedInputTokens = uncachedInputTokens,
        cacheWriteTokens = cacheWriteTokens,
        hasCacheWriteTokens = hasCacheWriteTokens,
        cachedTokens = cachedTokens,
        outputTokens = outputTokens,
        reasoningTokens = reasoningTokens,
        compactionCount = compactionCount,
        compactionPromptTokens = compactionPromptTokens,
        compactionUncachedTokens = compactionUncachedTokens,
        compactionCacheWriteTokens = compactionCacheWriteTokens,
        compactionCachedTokens = compactionCachedTokens,
        compactionOutputTokens = compactionOutputTokens,
        lastCompactionPromptTokens = lastCompactionPromptTokens,
        lastCompactionCachedTokens = lastCompactionCachedTokens,
        lastCompactionCacheWriteTokens = lastCompactionCacheWriteTokens,
        lastCompactionOutputTokens = lastCompactionOutputTokens,
        lastCompactionArchiveId = lastCompactionArchiveId,
        lastCompactionPaybackTurns = payback?.paybackTurns,
        lastCompactionTurnsAfter = payback?.turnsAfter ?: 0,
        lastCompactionPaidBack = payback?.paidBack ?: false,
        subagentCount = subagentCount,
        subagentPromptTokens = subagentPromptTokens,
        subagentCachedTokens = subagentCachedTokens,
        subagentOutputTokens = subagentOutputTokens,
        ttlOrPrefixMissTokens = diagnostics.attribution.ttlOrPrefixMissTokens,
        compactionRepayTokens = diagnostics.attribution.compactionRepayTokens,
        hasTokenUsage = hasTokenUsage,
    )
}

internal fun formatCacheHitRate(cachedTokens: Int, promptTokens: Int, locale: Locale = Locale.getDefault()): String? {
    if (cachedTokens < 0 || promptTokens <= 0) return null
    val rate = (cachedTokens.toDouble() / promptTokens.toDouble() * 100.0).coerceIn(0.0, 100.0)
    return NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = if (rate == rate.toLong().toDouble()) 0 else 1
        maximumFractionDigits = 1
    }.format(rate) + "%"
}

internal fun formatContextSnapshot(
    contextTokens: Int?,
    contextWindow: Int?,
    locale: Locale = Locale.getDefault(),
): String? {
    if (contextTokens == null || contextTokens < 0 || contextWindow == null || contextWindow <= 0) return null
    return "${formatCompactTokenCount(contextTokens, locale)}/${formatCompactTokenCount(contextWindow, locale)}"
}

internal fun estimateStreamingTokensPerSecond(tokens: Int, elapsedMs: Long): Double? {
    if (tokens <= 0 || elapsedMs <= 0L) return null
    return tokens * 1000.0 / elapsedMs
}

internal fun contextUsageProgress(contextTokens: Int?, contextWindow: Int?): Float? {
    if (contextTokens == null || contextTokens < 0 || contextWindow == null || contextWindow <= 0) {
        return null
    }
    return (contextTokens.toFloat() / contextWindow.toFloat()).coerceIn(0f, 1f)
}

internal fun formatContextUsage(
    usage: AgentContextUsageUi,
    noUsageText: String = "No usage data yet",
    noLimitText: String = "Context window unknown, so automatic compaction is off",
    locale: Locale = Locale.getDefault(),
): String = when {
    usage.contextWindow == null || usage.contextWindow <= 0 -> noLimitText
    usage.contextTokens == null -> noUsageText
    else -> {
        val percent = usage.contextTokens.toDouble() / usage.contextWindow.toDouble() * 100.0
        val percentFormat = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }
        "${formatCompactTokenCount(usage.contextTokens, locale)} / " +
            "${formatCompactTokenCount(usage.contextWindow, locale)} tokens · " +
            "${percentFormat.format(percent)}%"
    }
}

internal fun formatCompactTokenCount(value: Int, locale: Locale = Locale.getDefault()): String {
    val absolute = kotlin.math.abs(value.toLong())
    val divisor = when {
        absolute >= 1_000_000 -> 1_000_000.0
        absolute >= 1_000 -> 1_000.0
        else -> return NumberFormat.getIntegerInstance(locale).format(value)
    }
    val suffix = if (divisor == 1_000_000.0) "M" else "K"
    val formatted = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
        isGroupingUsed = false
    }.format(value / divisor)
    return "$formatted$suffix"
}
