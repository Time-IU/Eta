package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.SessionStatsSummaryUi
import io.github.mangi.eta.ui.model.TokenUsageUi
import org.junit.Assert.*
import org.junit.Test

class UnifiedUsagePolicyTest {
    private fun rows(usage: AgentContextUsageUi, scope: UnifiedUsageScope) =
        unifiedUsageSections(usage).single { it.scope == scope }.rows.associateBy { it.metric }
    private fun session(stats: SessionStatsSummaryUi, latest: TokenUsageUi? = null) =
        rows(AgentContextUsageUi(200, 1000, stats, latestUsage = latest), UnifiedUsageScope.SESSION)

    @Test fun latestNeverGetsAddedToSessionAgain() {
        val stats = SessionStatsSummaryUi(promptTokens = 100, outputTokens = 20,
            subagentCount = 1, subagentPromptTokens = 7, subagentOutputTokens = 3,
            compactionCount = 2, compactionPromptTokens = 9, compactionOutputTokens = 1,
            hasTokenUsage = true)
        val values = session(stats, TokenUsageUi(inputTokens = 9999, outputTokens = 8888))
        assertEquals("140 tok", values.getValue(UnifiedUsageMetric.TOTAL).value)
        assertEquals("100 tok", values.getValue(UnifiedUsageMetric.INPUT).value)
        assertEquals("7 tok", values.getValue(UnifiedUsageMetric.SUBAGENT_INPUT).value)
    }
    @Test fun noLatestKeepsAllMissingFieldsUnknown() {
        val latest = rows(AgentContextUsageUi(null, null), UnifiedUsageScope.LATEST)
        assertEquals(15, latest.size)
        assertTrue(latest.values.all { it.value == null })
    }
    @Test fun missingContextComponentsAreNotInventedFromTotal() {
        val current = rows(AgentContextUsageUi(300, 1000, systemPromptTokens = 15), UnifiedUsageScope.CURRENT)
        assertEquals("30.0%", current.getValue(UnifiedUsageMetric.CONTEXT_RATIO).value)
        assertEquals("15 tok", current.getValue(UnifiedUsageMetric.SYSTEM).value)
        assertTrue(current.getValue(UnifiedUsageMetric.SYSTEM).estimated)
        assertNull(current.getValue(UnifiedUsageMetric.TOOLS).value)
        assertNull(current.getValue(UnifiedUsageMetric.MESSAGES).value)
    }
    @Test fun contextOverflowRatioDoesNotClampToOneHundred() {
        val current = rows(AgentContextUsageUi(1200, 1000), UnifiedUsageScope.CURRENT)
        assertEquals("120.0%", current.getValue(UnifiedUsageMetric.CONTEXT_RATIO).value)
    }
    @Test fun latestPreservesRawZeroAndReasoningWithoutAddingIt() {
        val u = TokenUsageUi(inputTokens = 100, cachedTokens = 0, cacheWriteTokens = 0,
            outputTokens = 20, reasoningTokens = 5)
        val latest = rows(AgentContextUsageUi(null, 1000, latestUsage = u), UnifiedUsageScope.LATEST)
        assertEquals("0 tok", latest.getValue(UnifiedUsageMetric.CACHE_READ).value)
        assertEquals("0 tok", latest.getValue(UnifiedUsageMetric.CACHE_WRITE).value)
        assertEquals("0.0%", latest.getValue(UnifiedUsageMetric.CACHE_HIT).value)
        assertEquals("5 tok", latest.getValue(UnifiedUsageMetric.REASONING).value)
        assertEquals("120 tok", latest.getValue(UnifiedUsageMetric.TOTAL).value)
    }
    @Test fun missingLatestCompactionNeverShowsCumulativeAsLatest() {
        val values = session(SessionStatsSummaryUi(compactionCount = 3,
            compactionPromptTokens = 900, compactionOutputTokens = 100))
        assertEquals("1000 tok", values.getValue(UnifiedUsageMetric.COMPACTION_TOTAL).value)
        assertNull(values.getValue(UnifiedUsageMetric.LAST_COMPACTION_INPUT).value)
        assertNull(values.getValue(UnifiedUsageMetric.PAID_BACK).value)
    }
    @Test fun allOldPopoverUniqueDiagnosticsAndPerformanceSurvive() {
        val values = session(SessionStatsSummaryUi(turns = 4, steps = 9, llmMs = 1000,
            toolMs = 200, ttftMs = 90, ttftSteps = 3, decodeMs = 500, decodeTokens = 20,
            hasTokenUsage = true, promptTokens = 100, cachedTokens = 40,
            cacheWriteTokens = 3, hasCacheWriteTokens = true, outputTokens = 30, reasoningTokens = 10,
            compactionCount = 2, lastCompactionPromptTokens = 80, lastCompactionCachedTokens = 20,
            lastCompactionArchiveId = "archive-7", lastCompactionPaybackTurns = 5,
            lastCompactionTurnsAfter = 2, lastCompactionPaidBack = false,
            subagentCount = 2, subagentPromptTokens = 50, subagentCachedTokens = 25,
            subagentOutputTokens = 10, ttlOrPrefixMissTokens = 234, compactionRepayTokens = 123))
        val expected = mapOf(UnifiedUsageMetric.TURNS to "4", UnifiedUsageMetric.STEPS to "9",
            UnifiedUsageMetric.MODEL_TIME to "1000 ms", UnifiedUsageMetric.TOOL_TIME to "200 ms",
            UnifiedUsageMetric.TTFT to "30 ms", UnifiedUsageMetric.TPS to "40.0 tok/s",
            UnifiedUsageMetric.CACHE_HIT to "40.0%", UnifiedUsageMetric.CACHE_WRITE to "3 tok",
            UnifiedUsageMetric.OUTPUT to "30 tok", UnifiedUsageMetric.REASONING to "10 tok",
            UnifiedUsageMetric.ARCHIVE to "archive-7", UnifiedUsageMetric.PAYBACK_TURNS to "5",
            UnifiedUsageMetric.TURNS_AFTER to "2", UnifiedUsageMetric.PAID_BACK to "false",
            UnifiedUsageMetric.SUBAGENT_COUNT to "2", UnifiedUsageMetric.SUBAGENT_HIT to "50.0%",
            UnifiedUsageMetric.NON_INCREMENTAL_MISS to "234 tok", UnifiedUsageMetric.COMPACTION_REPAY to "123 tok")
        expected.forEach { (key, value) -> assertEquals(key.name, value, values.getValue(key).value) }
    }
    @Test fun latestPerformanceSupportsBothExistingFieldFamilies() {
        val latest = rows(AgentContextUsageUi(null, null, latestUsage = TokenUsageUi(outputTokens = 40,
            llmMs = 1100, toolMs = 90, ttftMs = 300, ttftSteps = 3, decodeMs = 500,
            decodeTokens = 20)), UnifiedUsageScope.LATEST)
        assertEquals("100 ms", latest.getValue(UnifiedUsageMetric.TTFT).value)
        assertEquals("40.0 tok/s", latest.getValue(UnifiedUsageMetric.TPS).value)
        assertEquals("90 ms", latest.getValue(UnifiedUsageMetric.TOOL_TIME).value)
    }
    @Test fun speedDoesNotAssumeUnknownFirstTokenLatencyIsZero() {
        val latest = rows(AgentContextUsageUi(null, null, latestUsage = TokenUsageUi(
            outputTokens = 40, requestDurationMs = 1000)), UnifiedUsageScope.LATEST)
        assertNull(latest.getValue(UnifiedUsageMetric.TPS).value)
    }
    @Test fun sessionTtftAndSpeedAreWeightedNotAveragedPerTurn() {
        val values = session(SessionStatsSummaryUi(ttftMs = 100, ttftSteps = 4,
            decodeMs = 500, decodeTokens = 100))
        assertEquals("25 ms", values.getValue(UnifiedUsageMetric.TTFT).value)
        assertEquals("200.0 tok/s", values.getValue(UnifiedUsageMetric.TPS).value)
    }
    @Test fun missingSessionReportDoesNotBecomeZeroTokens() {
        val values = session(SessionStatsSummaryUi())
        assertNull(values.getValue(UnifiedUsageMetric.TOTAL).value)
        assertNull(values.getValue(UnifiedUsageMetric.CACHE_WRITE).value)
        assertEquals("0", values.getValue(UnifiedUsageMetric.TURNS).value)
    }
}
