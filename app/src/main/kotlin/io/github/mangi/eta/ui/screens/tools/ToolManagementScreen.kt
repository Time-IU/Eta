package io.github.mangi.eta.ui.screens.tools

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.coui.CouiSegmentedTabs
import io.github.mangi.eta.ui.model.AgentToolsAction
import io.github.mangi.eta.ui.model.AgentToolsUiState
import top.yukonga.miuix.kmp.basic.TextButton

@Composable
internal fun ToolManagementScreen(state: AgentToolsUiState, onAction: (AgentToolsAction) -> Unit,
    onBack: () -> Unit, onManageGroups: () -> Unit, initialPolicy: Boolean = false) {
    var policy by remember(initialPolicy) { mutableStateOf(initialPolicy) }
    var dirty by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var exitAfterDiscard by remember { mutableStateOf(false) }
    val leave: () -> Unit = { if (dirty) { exitAfterDiscard = true; discard = true } else onBack() }
    if (androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current != null) {
        androidx.activity.compose.BackHandler(enabled = dirty && !discard) { leave() }
    }
    val items = listOf(false to stringResource(R.string.compact_tools_available), true to stringResource(R.string.compact_tools_policy))
    val select: (Boolean) -> Unit = { next ->
        if (next != policy) { if (dirty) { exitAfterDiscard = false; discard = true } else policy = next }
    }
    val managementHeader: @Composable () -> Unit = {
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            CouiSegmentedTabs(items = items, selectedKey = policy, onSelectedKeyChange = select,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        } else {
            top.yukonga.miuix.kmp.basic.TabRow(tabs = items.map { it.second },
                selectedTabIndex = if (policy) 1 else 0, onTabSelected = { select(it == 1) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
    if (policy) io.github.mangi.eta.ui.screens.prompts.PromptToolsScreen(onBack = leave,
        onManageGroups = onManageGroups, onDirtyChanged = { dirty = it }, managementHeader = managementHeader)
    else AgentToolsScreen(state, onAction, managementHeader = managementHeader)
    if (discard) {
        val confirm: () -> Unit = {
            discard = false; dirty = false
            if (exitAfterDiscard) onBack() else policy = false
        }
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            io.github.mangi.eta.ui.components.coui.CouiConfirmDialog(visible = true,
                title = stringResource(R.string.compact_tools_unsaved),
                message = stringResource(R.string.compact_tools_policy_summary),
                confirmText = stringResource(R.string.compact_tools_discard),
                onConfirm = confirm, onDismissRequest = { discard = false })
        } else io.github.mangi.eta.ui.components.EtaWindowDialog(show = true,
            title = stringResource(R.string.compact_tools_unsaved), onDismissRequest = { discard = false }) {
            MiuixDialogActions(confirmText = stringResource(R.string.compact_tools_discard),
                onCancel = { discard = false }, onConfirm = confirm)
        }
    }
}
