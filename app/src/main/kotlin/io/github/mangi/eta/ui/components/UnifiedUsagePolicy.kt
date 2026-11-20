package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.CacheUsageDiagnostics
import java.util.Locale

/** Projection only: never adds latestUsage to the already aggregated sessionStats. */
internal enum class UnifiedUsageScope { CURRENT, LATEST, SESSION }
internal enum class UnifiedUsageMetric {
    CONTEXT_USED, CONTEXT_LIMIT, CONTEXT_RATIO, SYSTEM, TOOLS, MESSAGES,
    INPUT, UNCACHED, CACHE_READ, CACHE_WRITE, CACHE_HIT, OUTPUT, REASONING, TOTAL,
    REQUEST_COUNT, STEPS, TURNS, MODEL_TIME, TOOL_TIME, REQUEST_TIME, TTFT, TPS,
    COMPACTION_COUNT, COMPACTION_INPUT, COMPACTION_UNCACHED, COMPACTION_READ,
    COMPACTION_WRITE, COMPACTION_OUTPUT, COMPACTION_TOTAL, LAST_COMPACTION_INPUT,
    LAST_COMPACTION_READ, LAST_COMPACTION_WRITE, LAST_COMPACTION_OUTPUT,
    LAST_COMPACTION_HIT, ARCHIVE, PAYBACK_TURNS, TURNS_AFTER, PAID_BACK,
    SUBAGENT_COUNT, SUBAGENT_INPUT, SUBAGENT_READ, SUBAGENT_OUTPUT, SUBAGENT_HIT,
    NON_INCREMENTAL_MISS, COMPACTION_REPAY,
}
internal data class UnifiedUsageRow(
    val metric: UnifiedUsageMetric,
    val value: String?,
    val estimated: Boolean = false,
)
internal data class UnifiedUsageSection(val scope: UnifiedUsageScope, val rows: List<UnifiedUsageRow>)

internal fun unifiedUsageSections(usage: AgentContextUsageUi): List<UnifiedUsageSection> {
    fun tok(n: Int?) = n?.takeIf { it >= 0 }?.let { "$it tok" }
    fun ms(n: Long?) = n?.takeIf { it >= 0 }?.let { "$it ms" }
    fun percent(read: Int?, input: Int?): String? =
        if (read != null && input != null && read >= 0 && input > 0 && read <= input)
            String.format(Locale.ROOT, "%.1f%%", read * 100.0 / input) else null
    fun speed(tokens: Int?, duration: Long?): String? =
        if (tokens != null && tokens > 0 && duration != null && duration > 0)
            String.format(Locale.ROOT, "%.1f tok/s", tokens * 1000.0 / duration) else null
    fun row(metric: UnifiedUsageMetric, value: String?, estimated: Boolean = false) =
        UnifiedUsageRow(metric, value, estimated)
    val current = listOf(
        row(UnifiedUsageMetric.CONTEXT_USED, tok(usage.contextTokens)),
        row(UnifiedUsageMetric.CONTEXT_LIMIT, tok(usage.contextWindow?.takeIf { it > 0 })),
        row(UnifiedUsageMetric.CONTEXT_RATIO,
            if (usage.contextTokens != null && usage.contextWindow != null && usage.contextWindow > 0)
                String.format(Locale.ROOT, "%.1f%%", usage.contextTokens * 100.0 / usage.contextWindow) else null),
        row(UnifiedUsageMetric.SYSTEM, tok(usage.systemPromptTokens), true),
        row(UnifiedUsageMetric.TOOLS, tok(usage.toolDefinitionTokens), true),
        row(UnifiedUsageMetric.MESSAGES, tok(usage.conversationTokens), true),
    )
    val u = usage.latestUsage
    // Exactly the same normalizer used by deriveSessionStatsSummary. It accepts
    // normalized inclusive input, or the legacy exclusive-input heuristic. Never
    // use the old TokenUsageUi read-only subtraction (which counts cache writes twice).
    // No input/cache record means unknown request input, not a context-derived substitute.
    val prompt = u?.takeIf {
        it.inputTokens != null || it.cachedTokens != null || it.cacheWriteTokens != null
    }?.let(CacheUsageDiagnostics::resolvePromptBreakdown)
    val latestTotal = listOfNotNull(prompt?.promptTokens, u?.outputTokens).takeIf {
        it.isNotEmpty()
    }?.sum()
    // Preserve the legacy panel's run-average TTFT fallback, but don't invent TTFT=0 for TPS.
    val latestTtft = u?.firstTokenAverageMs ?: u?.ttftMs?.let { total ->
        val count = u.ttftSteps ?: 1
        if (count > 0) total / count else null
    }
    val latestSpeed = speed(u?.decodeTokens ?: u?.outputTokens, u?.decodeMs)
        ?: if (u?.firstTokenMs != null && u.requestDurationMs != null &&
            u.firstTokenMs >= 0 && u.requestDurationMs > u.firstTokenMs)
            speed(u.outputTokens, u.requestDurationMs - u.firstTokenMs) else null
    val latest = listOf(
        row(UnifiedUsageMetric.INPUT, tok(prompt?.promptTokens), true),
        row(UnifiedUsageMetric.UNCACHED, tok(prompt?.uncachedInputTokens), true),
        row(UnifiedUsageMetric.CACHE_READ, tok(if (u?.cachedTokens != null) prompt?.cacheReadTokens else null)),
        row(UnifiedUsageMetric.CACHE_WRITE, tok(if (u?.cacheWriteTokens != null) prompt?.cacheWriteTokens else null)),
        row(UnifiedUsageMetric.CACHE_HIT, percent(if (u?.cachedTokens != null) prompt?.cacheReadTokens else null, prompt?.promptTokens), true),
        row(UnifiedUsageMetric.OUTPUT, tok(u?.outputTokens)),
        row(UnifiedUsageMetric.REASONING, tok(u?.reasoningTokens)),
        row(UnifiedUsageMetric.TOTAL, tok(latestTotal), true),
        row(UnifiedUsageMetric.REQUEST_COUNT, u?.requestCount?.toString()),
        row(UnifiedUsageMetric.STEPS, u?.stepCount?.toString()),
        row(UnifiedUsageMetric.MODEL_TIME, ms(u?.llmMs)),
        row(UnifiedUsageMetric.TOOL_TIME, ms(u?.toolDurationMs ?: u?.toolMs)),
        row(UnifiedUsageMetric.REQUEST_TIME, ms(u?.requestDurationMs)),
        row(UnifiedUsageMetric.TTFT, ms(latestTtft)),
        row(UnifiedUsageMetric.TPS, latestSpeed),
    )
    val s = usage.sessionStats
    fun sessionTok(n: Int) = if (s.hasTokenUsage) tok(n) else null
    val session = mutableListOf(
        row(UnifiedUsageMetric.TOTAL, sessionTok(s.totalTokens)),
        row(UnifiedUsageMetric.INPUT, sessionTok(s.promptTokens), true),
        row(UnifiedUsageMetric.UNCACHED, sessionTok(s.uncachedInputTokens), true),
        row(UnifiedUsageMetric.CACHE_READ, sessionTok(s.cachedTokens)),
        row(UnifiedUsageMetric.CACHE_WRITE, if (s.hasCacheWriteTokens) tok(s.cacheWriteTokens) else null),
        row(UnifiedUsageMetric.CACHE_HIT, if (s.hasTokenUsage) percent(s.cachedTokens, s.promptTokens) else null, true),
        row(UnifiedUsageMetric.OUTPUT, sessionTok(s.outputTokens)),
        row(UnifiedUsageMetric.REASONING, sessionTok(s.reasoningTokens)),
        row(UnifiedUsageMetric.TURNS, s.turns.toString()),
        row(UnifiedUsageMetric.STEPS, s.steps.toString()),
        row(UnifiedUsageMetric.MODEL_TIME, ms(s.llmMs.takeIf { it > 0 })),
        row(UnifiedUsageMetric.TOOL_TIME, ms(s.toolMs.takeIf { it > 0 })),
        row(UnifiedUsageMetric.TTFT, if (s.ttftSteps > 0) ms(s.ttftMs / s.ttftSteps) else null),
        row(UnifiedUsageMetric.TPS, speed(s.decodeTokens, s.decodeMs)),
        row(UnifiedUsageMetric.SUBAGENT_COUNT, s.subagentCount.toString()),
        row(UnifiedUsageMetric.COMPACTION_COUNT, s.compactionCount.toString()),
    )
    if (s.subagentCount > 0) session += listOf(
        row(UnifiedUsageMetric.SUBAGENT_INPUT, tok(s.subagentPromptTokens)),
        row(UnifiedUsageMetric.SUBAGENT_READ, tok(s.subagentCachedTokens)),
        row(UnifiedUsageMetric.SUBAGENT_OUTPUT, tok(s.subagentOutputTokens)),
        row(UnifiedUsageMetric.SUBAGENT_HIT, percent(s.subagentCachedTokens, s.subagentPromptTokens), true),
    )
    if (s.compactionCount > 0) session += listOf(
        row(UnifiedUsageMetric.COMPACTION_INPUT, tok(s.compactionPromptTokens)),
        row(UnifiedUsageMetric.COMPACTION_UNCACHED, tok(s.compactionUncachedTokens), true),
        row(UnifiedUsageMetric.COMPACTION_READ, tok(s.compactionCachedTokens)),
        row(UnifiedUsageMetric.COMPACTION_WRITE, tok(s.compactionCacheWriteTokens)),
        row(UnifiedUsageMetric.COMPACTION_OUTPUT, tok(s.compactionOutputTokens)),
        row(UnifiedUsageMetric.COMPACTION_TOTAL, tok(s.compactionPromptTokens + s.compactionOutputTokens)),
        // No cumulative-as-latest fallback: a missing latest record stays unknown.
        row(UnifiedUsageMetric.LAST_COMPACTION_INPUT, tok(s.lastCompactionPromptTokens)),
        row(UnifiedUsageMetric.LAST_COMPACTION_READ, tok(s.lastCompactionCachedTokens)),
        row(UnifiedUsageMetric.LAST_COMPACTION_WRITE, tok(s.lastCompactionCacheWriteTokens)),
        row(UnifiedUsageMetric.LAST_COMPACTION_OUTPUT, tok(s.lastCompactionOutputTokens)),
        row(UnifiedUsageMetric.LAST_COMPACTION_HIT, percent(s.lastCompactionCachedTokens, s.lastCompactionPromptTokens), true),
        row(UnifiedUsageMetric.ARCHIVE, s.lastCompactionArchiveId.takeIf { it.isNotBlank() }),
        row(UnifiedUsageMetric.PAYBACK_TURNS, s.lastCompactionPaybackTurns?.toString(), true),
        row(UnifiedUsageMetric.TURNS_AFTER, s.lastCompactionTurnsAfter.toString()),
        row(UnifiedUsageMetric.PAID_BACK, if (s.lastCompactionPaybackTurns != null)
            s.lastCompactionPaidBack.toString() else null, true),
    )
    session += listOf(
        row(UnifiedUsageMetric.NON_INCREMENTAL_MISS, if (s.hasTokenUsage) tok(s.ttlOrPrefixMissTokens) else null, true),
        row(UnifiedUsageMetric.COMPACTION_REPAY, if (s.hasTokenUsage) tok(s.compactionRepayTokens) else null, true),
    )
    return listOf(UnifiedUsageSection(UnifiedUsageScope.CURRENT, current),
        UnifiedUsageSection(UnifiedUsageScope.LATEST, latest), UnifiedUsageSection(UnifiedUsageScope.SESSION, session))
}
