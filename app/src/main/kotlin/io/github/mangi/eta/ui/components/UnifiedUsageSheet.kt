package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Compact, scope-labelled statistics. Projection and compaction semantics are unchanged. */
@Composable
internal fun UnifiedUsageSheet(show: Boolean, usage: AgentContextUsageUi, canCompact: Boolean,
    onCompact: () -> Unit, onDismiss: () -> Unit) {
    if (!show) return
    var help by remember { mutableStateOf<Pair<UnifiedUsageScope, UnifiedUsageMetric>?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val sections = remember(usage) { unifiedUsageSections(usage) }
    val current = sections.first { it.scope == UnifiedUsageScope.CURRENT }
    val session = sections.first { it.scope == UnifiedUsageScope.SESSION }
    val ink = usageInk()
    val muted = ink.copy(alpha = 0.64f)
    fun value(scope: UnifiedUsageSection, metric: UnifiedUsageMetric): String? = scope.rows.firstOrNull { it.metric == metric }?.value
    UsageCard(onDismiss) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Storage, null, tint = ink, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.refine_usage_tokens), style = MiuixTheme.textStyles.subtitle.copy(fontSize = 16.sp, lineHeight = 22.sp),
                    color = ink, modifier = Modifier.weight(1f))
                Text(usageDisplayValue(value(session, UnifiedUsageMetric.TOTAL)) ?: stringResource(R.string.unified_usage_unknown),
                    style = MiuixTheme.textStyles.body1.copy(fontSize = 14.sp, lineHeight = 20.sp), color = ink, textAlign = TextAlign.End)
            }
            UsageDivider()
            val contextText = listOfNotNull(value(current, UnifiedUsageMetric.CONTEXT_USED),
                value(current, UnifiedUsageMetric.CONTEXT_LIMIT)).let { values ->
                if (values.size == 2) values.joinToString(" / ") { usageDisplayValue(it, compact = true) ?: it } +
                    (value(current, UnifiedUsageMetric.CONTEXT_RATIO)?.let { " · $it" } ?: "") else null
            }
            UsageMetricRow(stringResource(R.string.context_panel_label), contextText,
                onHelp = { help = UnifiedUsageScope.CURRENT to UnifiedUsageMetric.CONTEXT_USED })
            val mainMetrics = listOf(UnifiedUsageMetric.CACHE_HIT, UnifiedUsageMetric.UNCACHED,
                UnifiedUsageMetric.CACHE_WRITE, UnifiedUsageMetric.CACHE_READ, UnifiedUsageMetric.OUTPUT)
            mainMetrics.forEach { metric ->
                var text = usageDisplayValue(value(session, metric))
                if (metric == UnifiedUsageMetric.OUTPUT) value(session, UnifiedUsageMetric.REASONING)?.let {
                    text = listOfNotNull(text, stringResource(R.string.refine_usage_reasoning, usageDisplayValue(it) ?: it)).joinToString(" · ")
                }
                UsageMetricRow(stringResource(compactUsageMetricLabel(metric)), text,
                    onHelp = { help = UnifiedUsageScope.SESSION to metric })
            }
            if (usage.sessionStats.subagentCount > 0) {
                UsageMetricRow(stringResource(R.string.refine_usage_subagents, usage.sessionStats.subagentCount),
                    usageDisplayValue((usage.sessionStats.subagentPromptTokens.toLong() + usage.sessionStats.subagentOutputTokens).toString() + " tok") +
                        (value(session, UnifiedUsageMetric.SUBAGENT_HIT)?.let { " · $it" } ?: ""),
                    onHelp = { help = UnifiedUsageScope.SESSION to UnifiedUsageMetric.SUBAGENT_COUNT })
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Speed, null, tint = ink, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.refine_usage_session), style = MiuixTheme.textStyles.subtitle.copy(fontSize = 16.sp, lineHeight = 22.sp),
                    color = ink, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.refine_usage_turns_steps, usage.sessionStats.turns, usage.sessionStats.steps),
                    style = MiuixTheme.textStyles.body1.copy(fontSize = 14.sp, lineHeight = 20.sp), color = ink, textAlign = TextAlign.End)
            }
            UsageDivider()
            val timeMetrics = listOf(UnifiedUsageMetric.MODEL_TIME, UnifiedUsageMetric.TOOL_TIME,
                UnifiedUsageMetric.TTFT, UnifiedUsageMetric.TPS)
            timeMetrics.forEach { metric -> UsageMetricRow(stringResource(compactUsageMetricLabel(metric)),
                usageDisplayValue(value(session, metric)), onHelp = { help = UnifiedUsageScope.SESSION to metric }) }
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
                .padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (expanded) R.string.refine_usage_less else R.string.refine_usage_more),
                    style = MiuixTheme.textStyles.footnote1, color = muted)
            }
            if (expanded) {
                val shown = (mainMetrics + timeMetrics + listOf(UnifiedUsageMetric.TOTAL, UnifiedUsageMetric.REASONING,
                    UnifiedUsageMetric.TURNS, UnifiedUsageMetric.STEPS, UnifiedUsageMetric.SUBAGENT_COUNT)).toSet()
                sections.forEach { section ->
                    val title = when (section.scope) {
                        UnifiedUsageScope.CURRENT -> R.string.unified_usage_current
                        UnifiedUsageScope.LATEST -> R.string.unified_usage_latest
                        UnifiedUsageScope.SESSION -> R.string.unified_usage_session
                    }
                    Text(stringResource(title), style = MiuixTheme.textStyles.subtitle.copy(fontSize = 16.sp, lineHeight = 22.sp), color = ink,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                    section.rows.filterNot { section.scope == UnifiedUsageScope.SESSION && it.metric in shown }.forEach { row ->
                        UsageMetricRow(stringResource(compactUsageMetricLabel(row.metric)), usageDisplayValue(row.value),
                            onHelp = { help = section.scope to row.metric })
                    }
                }
            }
        }
        if (canCompact) UsageAction(stringResource(R.string.context_compact_action), primary = true) {
            onDismiss(); onCompact()
        }
        if (LocalUiStyle.current != AppearanceUiStyle.COUI)
            UsageAction(stringResource(R.string.action_close), primary = false, onDismiss)
    }
    help?.let { (scope, metric) ->
        UsageCard({ help = null }) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(compactUsageMetricLabel(metric)), style = MiuixTheme.textStyles.subtitle.copy(fontSize = 16.sp, lineHeight = 22.sp),
                    color = ink, fontWeight = FontWeight.SemiBold)
                Text(stringResource(usageScopeExplanation(scope)), style = MiuixTheme.textStyles.footnote1, color = muted)
                Text(stringResource(usageMetricExplanation(metric)), style = MiuixTheme.textStyles.body1.copy(fontSize = 14.sp, lineHeight = 20.sp), color = ink)
                // Each explanation is metric-specific; accounting details remain in the source policy.
            }
            if (LocalUiStyle.current != AppearanceUiStyle.COUI)
                UsageAction(stringResource(R.string.action_close), primary = false) { help = null }
        }
    }
}

@Composable private fun usageInk(): Color = if (LocalUiStyle.current == AppearanceUiStyle.COUI)
    if (CouiTheme.isDark) Color(0xFFF2F2F7) else Color(0xFF17191D) else MiuixTheme.colorScheme.onSurface

@Composable private fun UsageCard(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ComposerPanelDialog(true, onDismiss, showClose = false, content = content)
}

@Composable private fun UsageDivider() {
    Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(0.5.dp).background(usageInk().copy(alpha = 0.14f)))
}

@Composable private fun UsageMetricRow(label: String, value: String?, onHelp: () -> Unit) {
    CompactMetricRow(label, value ?: stringResource(R.string.unified_usage_unknown), onHelp)
}

@Composable private fun UsageAction(label: String, primary: Boolean, action: () -> Unit) {
    val m = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) CouiButton(text = label, onClick = action,
        modifier = m, role = if (primary) CouiButtonRole.Primary else CouiButtonRole.Secondary)
    else TextButton(text = label, onClick = action, modifier = m, colors = if (primary)
        top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColorsPrimary() else
        top.yukonga.miuix.kmp.basic.ButtonDefaults.textButtonColors())
}

private fun usageMetricExplanation(metric: UnifiedUsageMetric): Int = when (metric) {
    UnifiedUsageMetric.CONTEXT_USED, UnifiedUsageMetric.CONTEXT_LIMIT, UnifiedUsageMetric.CONTEXT_RATIO,
    UnifiedUsageMetric.SYSTEM, UnifiedUsageMetric.TOOLS, UnifiedUsageMetric.MESSAGES -> R.string.refine_help_context
    UnifiedUsageMetric.CACHE_HIT -> R.string.compact_help_hit
    UnifiedUsageMetric.CACHE_READ -> R.string.compact_help_read
    UnifiedUsageMetric.CACHE_WRITE -> R.string.compact_help_write
    UnifiedUsageMetric.UNCACHED -> R.string.compact_help_uncached
    UnifiedUsageMetric.INPUT -> R.string.compact_help_input
    UnifiedUsageMetric.OUTPUT -> R.string.compact_help_output
    UnifiedUsageMetric.REASONING -> R.string.compact_help_reasoning
    UnifiedUsageMetric.TOTAL -> R.string.compact_help_total
    UnifiedUsageMetric.TTFT -> R.string.refine_help_ttft
    UnifiedUsageMetric.TPS -> R.string.refine_help_tps
    UnifiedUsageMetric.MODEL_TIME, UnifiedUsageMetric.TOOL_TIME, UnifiedUsageMetric.REQUEST_TIME -> R.string.refine_help_time
    UnifiedUsageMetric.SUBAGENT_COUNT, UnifiedUsageMetric.SUBAGENT_INPUT, UnifiedUsageMetric.SUBAGENT_READ,
    UnifiedUsageMetric.SUBAGENT_OUTPUT, UnifiedUsageMetric.SUBAGENT_HIT -> R.string.refine_help_subagent
    UnifiedUsageMetric.TURNS, UnifiedUsageMetric.STEPS, UnifiedUsageMetric.REQUEST_COUNT -> R.string.refine_help_steps
    else -> R.string.refine_help_compaction
}

internal fun unifiedUsageMetricLabel(metric: UnifiedUsageMetric): Int = when (metric) {
    UnifiedUsageMetric.CONTEXT_USED -> R.string.unified_usage_context_used
    UnifiedUsageMetric.CONTEXT_LIMIT -> R.string.unified_usage_context_limit
    UnifiedUsageMetric.CONTEXT_RATIO -> R.string.unified_usage_context_ratio
    UnifiedUsageMetric.SYSTEM -> R.string.unified_usage_system
    UnifiedUsageMetric.TOOLS -> R.string.unified_usage_tools
    UnifiedUsageMetric.MESSAGES -> R.string.unified_usage_messages
    UnifiedUsageMetric.INPUT -> R.string.unified_usage_input
    UnifiedUsageMetric.UNCACHED -> R.string.unified_usage_uncached
    UnifiedUsageMetric.CACHE_READ -> R.string.unified_usage_cache_read
    UnifiedUsageMetric.CACHE_WRITE -> R.string.unified_usage_cache_write
    UnifiedUsageMetric.CACHE_HIT -> R.string.unified_usage_cache_hit
    UnifiedUsageMetric.OUTPUT -> R.string.unified_usage_output
    UnifiedUsageMetric.REASONING -> R.string.unified_usage_reasoning
    UnifiedUsageMetric.TOTAL -> R.string.unified_usage_total
    UnifiedUsageMetric.REQUEST_COUNT -> R.string.unified_usage_request_count
    UnifiedUsageMetric.STEPS -> R.string.unified_usage_steps
    UnifiedUsageMetric.TURNS -> R.string.unified_usage_turns
    UnifiedUsageMetric.MODEL_TIME -> R.string.unified_usage_model_time
    UnifiedUsageMetric.TOOL_TIME -> R.string.unified_usage_tool_time
    UnifiedUsageMetric.REQUEST_TIME -> R.string.unified_usage_request_time
    UnifiedUsageMetric.TTFT -> R.string.unified_usage_ttft
    UnifiedUsageMetric.TPS -> R.string.unified_usage_tps
    UnifiedUsageMetric.COMPACTION_COUNT -> R.string.unified_usage_compaction_count
    UnifiedUsageMetric.COMPACTION_INPUT -> R.string.unified_usage_compaction_input
    UnifiedUsageMetric.COMPACTION_UNCACHED -> R.string.unified_usage_compaction_uncached
    UnifiedUsageMetric.COMPACTION_READ -> R.string.unified_usage_compaction_read
    UnifiedUsageMetric.COMPACTION_WRITE -> R.string.unified_usage_compaction_write
    UnifiedUsageMetric.COMPACTION_OUTPUT -> R.string.unified_usage_compaction_output
    UnifiedUsageMetric.COMPACTION_TOTAL -> R.string.unified_usage_compaction_total
    UnifiedUsageMetric.LAST_COMPACTION_INPUT -> R.string.unified_usage_last_compaction_input
    UnifiedUsageMetric.LAST_COMPACTION_READ -> R.string.unified_usage_last_compaction_read
    UnifiedUsageMetric.LAST_COMPACTION_WRITE -> R.string.unified_usage_last_compaction_write
    UnifiedUsageMetric.LAST_COMPACTION_OUTPUT -> R.string.unified_usage_last_compaction_output
    UnifiedUsageMetric.LAST_COMPACTION_HIT -> R.string.unified_usage_last_compaction_hit
    UnifiedUsageMetric.ARCHIVE -> R.string.unified_usage_archive
    UnifiedUsageMetric.PAYBACK_TURNS -> R.string.unified_usage_payback_turns
    UnifiedUsageMetric.TURNS_AFTER -> R.string.unified_usage_turns_after
    UnifiedUsageMetric.PAID_BACK -> R.string.unified_usage_paid_back
    UnifiedUsageMetric.SUBAGENT_COUNT -> R.string.unified_usage_subagent_count
    UnifiedUsageMetric.SUBAGENT_INPUT -> R.string.unified_usage_subagent_input
    UnifiedUsageMetric.SUBAGENT_READ -> R.string.unified_usage_subagent_read
    UnifiedUsageMetric.SUBAGENT_OUTPUT -> R.string.unified_usage_subagent_output
    UnifiedUsageMetric.SUBAGENT_HIT -> R.string.unified_usage_subagent_hit
    UnifiedUsageMetric.NON_INCREMENTAL_MISS -> R.string.unified_usage_non_incremental_miss
    UnifiedUsageMetric.COMPACTION_REPAY -> R.string.unified_usage_compaction_repay
}

private fun usageScopeExplanation(scope: UnifiedUsageScope): Int = when (scope) {
    UnifiedUsageScope.CURRENT -> R.string.compact_scope_context
    UnifiedUsageScope.LATEST -> R.string.compact_scope_latest
    UnifiedUsageScope.SESSION -> R.string.compact_scope_session
}

internal fun compactUsageMetricLabel(metric: UnifiedUsageMetric): Int = when (metric) {
    UnifiedUsageMetric.CACHE_HIT -> R.string.compact_cache_hit
    UnifiedUsageMetric.UNCACHED -> R.string.compact_uncached
    UnifiedUsageMetric.CACHE_WRITE -> R.string.compact_cache_write
    UnifiedUsageMetric.CACHE_READ -> R.string.compact_cache_read
    UnifiedUsageMetric.MODEL_TIME -> R.string.compact_model_time
    UnifiedUsageMetric.TOOL_TIME -> R.string.compact_tool_time
    else -> unifiedUsageMetricLabel(metric)
}
