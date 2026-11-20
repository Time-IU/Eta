package io.github.mangi.eta.ui.screens.tasks

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.ui.components.EtaTextButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** A fixed status card: immutable explanation, last-completed outcome and centered action.
 * Ordinary preference saves never change the retry action label or enabled appearance.
 * All possible outcome labels are measured for current font/width, with hidden semantics removed.
 */
@Composable
internal fun CanvasSettingsStatusSlot(
    retrying: Boolean,
    actionFailed: Boolean,
    gateSync: SettingsDataStore.CanvasGateSync,
    readFailed: Boolean,
    reading: Boolean,
    onRetry: () -> Unit,
) {
    val outcomes = listOf(R.string.refine_canvas_read_failed, R.string.refine_canvas_reading,
        R.string.refine_canvas_action_failed, R.string.refine_canvas_sync_failed,
        R.string.refine_canvas_no_framework, R.string.refine_canvas_confirmed)
    val selected = when {
        readFailed -> R.string.refine_canvas_read_failed
        reading -> R.string.refine_canvas_reading
        actionFailed -> R.string.refine_canvas_action_failed
        gateSync == SettingsDataStore.CanvasGateSync.FAILED -> R.string.refine_canvas_sync_failed
        gateSync == SettingsDataStore.CanvasGateSync.UNAVAILABLE -> R.string.refine_canvas_no_framework
        else -> R.string.refine_canvas_confirmed
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.refine_canvas_explanation),
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            outcomes.forEach { message -> key(message) {
                Text(stringResource(message), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().then(if (message == selected) Modifier else
                        Modifier.alpha(0f).clearAndSetSemantics {}),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            } }
        }
        // Only an explicit retry owns this action state; unrelated saves must not flash it.
        EtaTextButton(
            text = stringResource(R.string.canvas_settings_read_retry),
            modifier = Modifier.fillMaxWidth(),
            enabled = !retrying && !reading,
            onClick = onRetry,
        )
    }
}
