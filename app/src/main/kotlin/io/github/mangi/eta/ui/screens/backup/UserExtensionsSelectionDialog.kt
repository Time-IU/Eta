package io.github.mangi.eta.ui.screens.backup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.coui.CouiCheckboxPreference
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.repository.UserExtensionInventory
import io.github.mangi.eta.data.repository.UserExtensionItem
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun UserExtensionsSelectionDialog(
    visible: Boolean,
    inventory: UserExtensionInventory?,
    selectedSkills: Set<String>,
    selectedSubagents: Set<String>,
    busy: Boolean,
    onToggleSkill: (String) -> Unit,
    onToggleSubagent: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible || inventory == null) return
    val selectedCount = selectedSkills.size + selectedSubagents.size
    val title = stringResource(R.string.data_backup_extensions_title)
    val summary = stringResource(R.string.data_backup_extensions_summary)
    val confirm = stringResource(if (busy) R.string.data_backup_working else R.string.action_confirm)
    val body: @Composable () -> Unit = {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            if (inventory.skills.isNotEmpty()) {
                item(key = "skills-heading") { SectionHeading(stringResource(R.string.data_backup_extensions_skills)) }
                items(inventory.skills, key = { "skill:${it.id}" }) { item ->
                    ExtensionRow(
                        item = item,
                        checked = item.id in selectedSkills,
                        enabled = !busy && !item.conflict,
                        conflictLabel = if (item.conflict) stringResource(R.string.data_backup_extensions_exists) else null,
                        onClick = { onToggleSkill(item.id) },
                    )
                }
            }
            if (inventory.subagents.isNotEmpty()) {
                item(key = "subagents-heading") { SectionHeading(stringResource(R.string.data_backup_extensions_subagents)) }
                items(inventory.subagents, key = { "subagent:${it.id}" }) { item ->
                    ExtensionRow(
                        item = item,
                        checked = item.id in selectedSubagents,
                        enabled = !busy && !item.conflict,
                        conflictLabel = if (item.conflict) stringResource(R.string.data_backup_extensions_exists) else null,
                        onClick = { onToggleSubagent(item.id) },
                    )
                }
            }
        }
    }
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiConfirmDialog(
            visible = true,
            title = title,
            message = summary,
            confirmText = confirm,
            confirmEnabled = !busy && selectedCount > 0,
            cancelEnabled = !busy,
            onConfirm = onConfirm,
            onDismissRequest = onDismiss,
            content = { body() },
        )
    } else {
        EtaWindowDialog(
            show = true,
            title = title,
            summary = summary,
            onDismissRequest = { if (!busy) onDismiss() },
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                body()
                MiuixDialogActions(
                    confirmText = confirm,
                    confirmEnabled = !busy && selectedCount > 0,
                    cancelEnabled = !busy,
                    onCancel = onDismiss,
                    onConfirm = onConfirm,
                )
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.subtitle,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ExtensionRow(
    item: UserExtensionItem,
    checked: Boolean,
    enabled: Boolean,
    conflictLabel: String?,
    onClick: () -> Unit,
) {
    val summary = conflictLabel ?: item.description
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiCheckboxPreference(
            title = item.name,
            summary = summary,
            checked = checked,
            enabled = enabled,
            onCheckedChange = { onClick() },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = item.name, style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (summary.isNotBlank()) Text(text = summary, style = MiuixTheme.textStyles.footnote2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(10.dp))
            Checkbox(state = if (checked) ToggleableState.On else ToggleableState.Off,
                enabled = enabled, onClick = onClick)
        }
    }
}
