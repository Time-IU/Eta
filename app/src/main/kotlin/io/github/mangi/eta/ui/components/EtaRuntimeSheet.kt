package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.text.CouiTextKind
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.runtime.EtaRuntimeDisplay
import io.github.mangi.eta.runtime.EtaRuntimeMonitor
import io.github.mangi.eta.runtime.EtaRuntimeSnapshot
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class RuntimeDetail(val kind: EtaRuntimeDisplay.Kind? = null, val pid: Int? = null, val start: Long? = null)

/** Read-only application metrics. No root request, cleaners, task killing or global services. */
@Composable
internal fun EtaRuntimeSheet(show: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var snapshot by remember { mutableStateOf(EtaRuntimeSnapshot()) }
    var failed by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(false) }
    var detail by remember(show) { mutableStateOf<RuntimeDetail?>(null) }
    var expanded by remember(show) { mutableStateOf(false) }
    LaunchedEffect(show, lifecycle, context) {
        snapshot = EtaRuntimeSnapshot()
        failed = false
        if (!show) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Reset CPU baselines on each foreground session, never span a stopped lifecycle.
            val monitor = EtaRuntimeMonitor(context)
            snapshot = EtaRuntimeSnapshot()
            active = true
            try {
                while (isActive) {
                    try {
                        snapshot = monitor.sample()
                        failed = false
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        snapshot = EtaRuntimeSnapshot()
                        failed = true
                    }
                    delay(1_500)
                }
            } finally {
                active = false
                snapshot = EtaRuntimeSnapshot()
            }
        }
    }
    val view = EtaRuntimeDisplay.project(snapshot.processes.map {
        EtaRuntimeDisplay.Input(it.pid, it.startTicks, it.name, it.isMain, it.pssKb, it.rssKb,
            it.cpuPercent, it.cpuReadable, it.memoryAgeMs)
    }, show && active, snapshot.sampled, failed)
    val selected = detail
    val title = when {
        selected?.pid != null -> stringResource(R.string.eta_runtime_process_title, selected.pid)
        selected?.kind != null -> metricLabel(EtaRuntimeDisplay.metricDetail(view, selected.kind).caption)
        selected != null -> stringResource(R.string.eta_runtime_details)
        else -> stringResource(R.string.eta_footprint_entry)
    }
    val dismiss: () -> Unit = { if (detail != null) detail = null else onDismiss() }
    RuntimeDialog(show, title, dismiss) {
        if (selected != null) RuntimeExplanation(snapshot, view, selected)
        else RuntimeBody(view, expanded, { expanded = !expanded }, { detail = it })
    }
}

/** One stable window for list and drill-down: no nested overlay host dependency. */
@Composable
private fun RuntimeDialog(show: Boolean, title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ComposerPanelDialog(show, onDismiss, title = title, content = content)
}

@Composable
private fun metricLabel(kind: EtaRuntimeDisplay.Kind): String = stringResource(when (kind) {
    EtaRuntimeDisplay.Kind.PSS -> R.string.eta_runtime_label_pss
    EtaRuntimeDisplay.Kind.RSS -> R.string.eta_runtime_label_rss
    EtaRuntimeDisplay.Kind.CPU -> R.string.eta_runtime_label_cpu
})

@Composable
private fun RuntimeBody(view: EtaRuntimeDisplay.View, expanded: Boolean, toggle: () -> Unit, explain: (RuntimeDetail) -> Unit) {
    RuntimeScrollColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (view.state != EtaRuntimeDisplay.State.READY) item("state") {
            RuntimeText(stringResource(if (view.state == EtaRuntimeDisplay.State.LOADING)
                R.string.eta_runtime_waiting else R.string.eta_runtime_empty))
        }
        items(view.summary, key = { it.kind.name }) { metric ->
            RuntimeMetric(metric) { explain(RuntimeDetail(kind = metric.kind)) }
        }
        if (view.rows.isNotEmpty()) {
            item("process-toggle") {
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = toggle).padding(vertical = 12.dp)) {
                    RuntimeText(stringResource(if (expanded) R.string.eta_runtime_collapse_processes
                        else R.string.eta_runtime_expand_processes, view.rows.size))
                }
            }
            if (expanded) items(view.rows, key = { "${it.process.pid}:${it.process.startTicks}" }) { row ->
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    explain(RuntimeDetail(pid = row.process.pid, start = row.process.startTicks))
                }.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    RuntimeText(stringResource(if (row.process.main) R.string.eta_runtime_main else R.string.eta_runtime_same_uid)
                        + " · PID ${row.process.pid}", heading = true)
                    row.metrics.forEach {
                        val cached = it.kind == EtaRuntimeDisplay.Kind.PSS && (it.requestAgeMs ?: 0) > 0
                        RuntimeText(metricLabel(it.kind) + if (cached)
                            " · " + stringResource(R.string.eta_runtime_cached) + "  " + metricValue(it)
                            else "  " + metricValue(it))
                    }
                }
            }
        }
        item("explain") {
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { explain(RuntimeDetail()) }.padding(vertical = 12.dp)) {
                RuntimeText(stringResource(R.string.eta_runtime_details))
            }
        }
    }
}

private fun metricValue(metric: EtaRuntimeDisplay.Metric): String = String.format(Locale.getDefault(),
    if (metric.kind == EtaRuntimeDisplay.Kind.CPU) "%.1f%%" else "%.1f MiB",
    if (metric.kind == EtaRuntimeDisplay.Kind.CPU) metric.value.toDouble() else metric.value.toDouble() / 1024.0)

@Composable
private fun RuntimeMetric(metric: EtaRuntimeDisplay.Metric, onClick: () -> Unit) {
    val cached = metric.kind == EtaRuntimeDisplay.Kind.PSS && (metric.requestAgeMs ?: 0) > 0
    CompactMetricRow(
        label = metricLabel(metric.kind) + if (cached) " · " + stringResource(R.string.eta_runtime_cached) else "",
        value = metricValue(metric),
        onHelp = onClick,
    )
}

@Composable
private fun RuntimeExplanation(snapshot: EtaRuntimeSnapshot, view: EtaRuntimeDisplay.View, selected: RuntimeDetail) {
    // Resolve identity against the CURRENT projection; never retain a vanished process or old metric value.
    val row = view.rows.firstOrNull { it.process.pid == selected.pid && it.process.startTicks == selected.start }
    val metricDetail = selected.kind?.let { EtaRuntimeDisplay.metricDetail(view, it) }
    val metrics = when {
        selected.pid != null -> row?.metrics.orEmpty()
        metricDetail != null -> listOfNotNull(metricDetail.current)
        else -> view.summary
    }
    if (selected.kind != null) {
        val explanation = when (selected.kind) {
            EtaRuntimeDisplay.Kind.CPU -> R.string.surface_help_cpu
            EtaRuntimeDisplay.Kind.PSS -> R.string.surface_help_pss
            EtaRuntimeDisplay.Kind.RSS -> R.string.surface_help_rss
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            RuntimeText(stringResource(explanation))
        }
        return
    }
    RuntimeScrollColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (selected.pid != null) item("process") {
            row?.process?.name?.takeIf { it.isNotBlank() }?.let { RuntimeText(it) }
            if (row == null) RuntimeText(stringResource(R.string.eta_runtime_empty))
        }
        if (metricDetail?.isEmpty == true) item("metric-empty") {
            RuntimeText(stringResource(R.string.eta_runtime_empty))
        }
        items(metrics, key = { it.kind.name }) { metric ->
            if (selected.kind == null) RuntimeText(metricLabel(metric.kind), heading = true)
            RuntimeText(stringResource(R.string.eta_runtime_coverage, metric.available, metric.total))
            metric.requestAgeMs?.let { RuntimeText(stringResource(R.string.eta_runtime_memory_age, it / 1_000)) }
        }
        item("scope") {
            RuntimeText(stringResource(R.string.eta_runtime_scope))
            RuntimeText(stringResource(R.string.eta_runtime_sources))
        }
        if (selected.kind == null || selected.kind == EtaRuntimeDisplay.Kind.PSS || selected.kind == EtaRuntimeDisplay.Kind.RSS) {
            item("memory") {
                RuntimeText(stringResource(R.string.eta_runtime_rss_note))
                RuntimeText(stringResource(R.string.eta_runtime_memory_note))
            }
        }
        if (selected.kind == null || selected.kind == EtaRuntimeDisplay.Kind.CPU) item("cpu") {
            RuntimeText(stringResource(R.string.eta_runtime_cpu_note))
        }
        item("visibility") {
            RuntimeText(stringResource(R.string.eta_runtime_visibility))
            if (snapshot.discoveryLimited) RuntimeText(stringResource(R.string.eta_runtime_partial))
        }
    }
}

@Composable
private fun RuntimeText(text: String, heading: Boolean = false) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        com.itos.coui.text.Text(text = text, modifier = Modifier.fillMaxWidth(),
            kind = if (heading) CouiTextKind.ItemTitle else CouiTextKind.ItemSummary,
            color = CouiTheme.colorScheme.onSurface)
    } else {
        Text(text = text, modifier = Modifier.fillMaxWidth(),
            style = if (heading) MiuixTheme.textStyles.body1 else MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurface)
    }
}

/** Eager only for the small already bounded metrics list; unlike LazyColumn it wraps short content. */
private class RuntimeScrollItems {
    @Composable fun item(key: String, content: @Composable () -> Unit) { androidx.compose.runtime.key(key) { content() } }
    @Composable fun <T> items(values: List<T>, key: (T) -> Any, content: @Composable (T) -> Unit) {
        values.forEach { value -> androidx.compose.runtime.key(key(value)) { content(value) } }
    }
}
@Composable
private fun RuntimeScrollColumn(modifier: Modifier, contentPadding: PaddingValues,
    verticalArrangement: Arrangement.Vertical, content: @Composable RuntimeScrollItems.() -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = verticalArrangement) { RuntimeScrollItems().content() }
}
