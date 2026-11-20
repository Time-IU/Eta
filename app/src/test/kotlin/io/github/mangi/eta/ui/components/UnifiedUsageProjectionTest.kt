package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.*
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import io.github.mangi.eta.ui.app.toUi
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real latestContextUsage + session projector + display policy, not duplicate formulas. */
class UnifiedUsageProjectionTest {
    private fun project(vararg records: AgentChatMessageUi) = latestContextUsage(records.toList(), null)
    private fun section(usage: AgentContextUsageUi, scope: UnifiedUsageScope) =
        unifiedUsageSections(usage).single { it.scope == scope }.rows.associate { it.metric to it.value }
    private fun message(id: String, usage: TokenUsageUi?) = AgentMessageUi(id, id, usage = usage)
    private val a = TokenUsageUi(contextTokens = 900, inputTokens = 100, outputTokens = 20,
        systemPromptTokens = 10, toolDefinitionTokens = 20, conversationTokens = 870)
    private val b = TokenUsageUi(inputTokens = 50, outputTokens = 7, firstTokenMs = 25,
        requestDurationMs = 100, requestCount = 1)

    @Test fun inclusiveInputSubtractsReadAndWriteOnceInBothScopes() {
        val recorded = TokenUsageUi(inputTokens = 100, cachedTokens = 20, cacheWriteTokens = 30, outputTokens = 10)
        val ui = project(message("run", recorded))
        val latest = section(ui, UnifiedUsageScope.LATEST)
        val session = section(ui, UnifiedUsageScope.SESSION)
        for (scope in listOf(latest, session)) {
            assertEquals("100 tok", scope[UnifiedUsageMetric.INPUT])
            assertEquals("50 tok", scope[UnifiedUsageMetric.UNCACHED])
            assertEquals("20 tok", scope[UnifiedUsageMetric.CACHE_READ])
            assertEquals("30 tok", scope[UnifiedUsageMetric.CACHE_WRITE])
            assertEquals("110 tok", scope[UnifiedUsageMetric.TOTAL])
            assertEquals("20.0%", scope[UnifiedUsageMetric.CACHE_HIT])
        }
        assertSame(recorded, ui.latestUsage)
        assertEquals(100, recorded.inputTokens)
    }
    @Test fun legacyExclusiveInputUsesSameExistingHeuristicInBothScopes() {
        val ui = project(message("legacy", TokenUsageUi(inputTokens = 10, cachedTokens = 20,
            cacheWriteTokens = 30, outputTokens = 5)))
        for (scope in UnifiedUsageScope.entries.filter { it != UnifiedUsageScope.CURRENT }) {
            val values = section(ui, scope)
            assertEquals("60 tok", values[UnifiedUsageMetric.INPUT])
            assertEquals("10 tok", values[UnifiedUsageMetric.UNCACHED])
            assertEquals("65 tok", values[UnifiedUsageMetric.TOTAL])
            assertEquals("33.3%", values[UnifiedUsageMetric.CACHE_HIT])
        }
    }
    @Test fun fullThenPartialSelectsBForLatestButAForContextSnapshot() {
        val ui = project(message("A", a), message("B", b))
        assertSame(b, ui.latestUsage)
        assertEquals(900, ui.contextTokens)
        assertEquals(10, ui.systemPromptTokens)
        assertEquals(870, ui.conversationTokens)
        assertEquals("50 tok", section(ui, UnifiedUsageScope.LATEST)[UnifiedUsageMetric.INPUT])
        assertEquals("25 ms", section(ui, UnifiedUsageScope.LATEST)[UnifiedUsageMetric.TTFT])
        assertEquals(150, ui.sessionStats.promptTokens)
    }
    @Test fun compactionClearsSnapshotButKeepsLatestBAndItsMetrics() {
        val ui = project(message("A", a), message("B", b), SystemNoticeMessageUi("compact",
            SystemNoticeCode.ContextCompaction, usage = TokenUsageUi(inputTokens = 800, outputTokens = 10)))
        assertSame(b, ui.latestUsage)
        assertNull(ui.contextTokens)
        assertNull(ui.systemPromptTokens)
        assertNull(ui.toolDefinitionTokens)
        assertNull(ui.conversationTokens)
        assertEquals("7 tok", section(ui, UnifiedUsageScope.LATEST)[UnifiedUsageMetric.OUTPUT])
        assertEquals(800, ui.sessionStats.compactionPromptTokens)
    }
    @Test fun partialAfterCompactionCannotResurrectPreCompactionSnapshot() {
        val ui = project(message("A", a), SystemNoticeMessageUi("compact", SystemNoticeCode.ContextCompaction), message("B", b))
        assertSame(b, ui.latestUsage)
        assertNull(ui.contextTokens)
        assertNull(ui.conversationTokens)
    }
    @Test fun newFullSnapshotAfterCompactionIsUsed() {
        val fresh = b.copy(contextTokens = 77, systemPromptTokens = 4, conversationTokens = 73)
        val ui = project(message("A", a), SystemNoticeMessageUi("compact", SystemNoticeCode.ContextCompaction), message("B", fresh))
        assertSame(fresh, ui.latestUsage)
        assertEquals(77, ui.contextTokens)
        assertEquals(4, ui.systemPromptTokens)
    }
    @Test fun emptyRecordIsSkippedButZeroAndPerformanceOnlyRecordsAreValid() {
        assertSame(b, project(message("B", b), message("empty", TokenUsageUi())).latestUsage)
        val zero = TokenUsageUi(inputTokens = 0, outputTokens = 0)
        assertSame(zero, project(message("B", b), message("zero", zero)).latestUsage)
        val performance = TokenUsageUi(firstTokenMs = 99)
        val ui = project(message("A", a), message("performance", performance))
        assertSame(performance, ui.latestUsage)
        assertNull(section(ui, UnifiedUsageScope.LATEST)[UnifiedUsageMetric.INPUT])
        assertEquals("99 ms", section(ui, UnifiedUsageScope.LATEST)[UnifiedUsageMetric.TTFT])
    }
    @Test fun multiRequestInputComesFromActualRunAccumulatorNotRawInputOrLastStep() {
        // Execute the real production accumulator. Reflection is test-only because it is private.
        val klass = Class.forName("io.github.mangi.eta.agent.model.AgentLoop\$TurnMetricsAccumulator")
        val accumulator = klass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val record = klass.declaredMethods.single { it.name == "recordStep" }.apply { isAccessible = true }
        fun step(normalizedPrompt: Int): AgentTokenUsage = record.invoke(accumulator, 100L, 10L, 90L,
            AgentTokenUsage(inputTokens = 5, cachedTokens = 20, cacheWriteTokens = 30, outputTokens = 10),
            normalizedPrompt) as AgentTokenUsage
        step(100)
        val run = step(200)
        assertEquals(300, run.inputTokens)
        assertEquals(2, run.requestCount)
        val stored = run.toUi()
        val ui = project(message("run", stored))
        for (scope in listOf(UnifiedUsageScope.LATEST, UnifiedUsageScope.SESSION)) {
            val values = section(ui, scope)
            assertEquals("300 tok", values[UnifiedUsageMetric.INPUT])
            assertEquals("200 tok", values[UnifiedUsageMetric.UNCACHED])
            assertEquals("40 tok", values[UnifiedUsageMetric.CACHE_READ])
            assertEquals("60 tok", values[UnifiedUsageMetric.CACHE_WRITE])
            assertEquals("320 tok", values[UnifiedUsageMetric.TOTAL])
        }
        assertEquals("2", section(ui, UnifiedUsageScope.LATEST)[UnifiedUsageMetric.REQUEST_COUNT])
        assertSame(stored, ui.latestUsage)
    }
}
