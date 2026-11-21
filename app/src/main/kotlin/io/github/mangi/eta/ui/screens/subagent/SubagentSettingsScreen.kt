package io.github.mangi.eta.ui.screens.subagent

import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.data.model.SubagentModelGrant
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogAction
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.dialog.Dialog
import com.itos.coui.icons.CouiIcons
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.model.SubagentSettings
import io.github.mangi.eta.data.model.enabledModel
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.EtaChoiceChip
import io.github.mangi.eta.ui.components.ListEmptyState
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.OptionalModelPickerContent
import io.github.mangi.eta.ui.components.StatusError
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiChipTabs
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiSearchBarBox
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import io.github.mangi.eta.ui.model.filterGroups
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import io.github.mangi.eta.ui.components.EtaWindowDialog as WindowDialog

private data class CustomSubagentDraft(
    val originalName: String? = null,
    val name: String = "",
    val description: String = "",
    val toolsText: String = "",
    val prompt: String = "",
)

@Composable
internal fun SubagentSettingsScreen(
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val agentPrefs = remember { Prefs.localAgentPreferences() }
    val defaultEnabled = Prefs.Keys.BOOLEAN_DEFAULTS.getValue(Prefs.Keys.AGENT_SUBAGENT_ENABLED)
    var subagentEnabled by remember(agentPrefs) {
        mutableStateOf(agentPrefs?.getBoolean(Prefs.Keys.AGENT_SUBAGENT_ENABLED, defaultEnabled) ?: defaultEnabled)
    }

    val providers by ProviderRepository.providersFlow().collectAsState(initial = emptyList())
    val primaryProviderId by RuntimeConfigRepository.selectedProviderIdFlow().collectAsState(initial = null)
    val primaryModelId by RuntimeConfigRepository.selectedModelIdFlow().collectAsState(initial = null)
    val subagentSettings by RuntimeConfigRepository.subagentSettingsFlow().collectAsState(initial = SubagentSettings())

    var customProfiles by remember { mutableStateOf<List<SubagentProfile>>(emptyList()) }
    fun reloadCustomProfiles() {
        scope.launch {
            customProfiles = withContext(Dispatchers.IO) {
                SubagentProfileCatalog.listCustom()
            }
        }
    }
    LaunchedEffect(Unit) {
        RuntimeConfigRepository.ensureDefaults(EtaApp.serviceInstance)
        reloadCustomProfiles()
    }

    val pickerState = remember(providers, primaryProviderId, primaryModelId) {
        AgentModelPickerProjector.project(providers, primaryProviderId, primaryModelId)
    }
    val resolvedSecondary = remember(providers, subagentSettings.modelId) {
        subagentSettings.modelId?.takeIf { it.isNotBlank() }?.let { id ->
            providers.enabledModel(id)
        }
    }
    val primaryModelName = pickerState.selectedModel?.displayName
    val secondarySummary = when {
        resolvedSecondary != null -> "${resolvedSecondary.provider.name} / ${resolvedSecondary.model.displayName}"
        !primaryModelName.isNullOrBlank() -> "跟随主模型 ($primaryModelName)"
        else -> "跟随主模型"
    }

    val effectiveCapabilities = remember(providers, resolvedSecondary, primaryProviderId, primaryModelId) {
        val target = resolvedSecondary ?: providers.enabledModel(primaryModelId)
        target?.let { (provider, model) ->
            RuntimeConfigRepository.reasoningCapabilities(provider, model)
        }
    }
    val effortOptions = remember(effectiveCapabilities) {
        val efforts = effectiveCapabilities?.selectableEfforts.orEmpty()
        listOf<ReasoningEffort?>(null) + efforts
    }
    val effortLabels = remember(effortOptions) {
        effortOptions.map { effort ->
            when (effort) {
                null -> "跟随模型默认"
                ReasoningEffort.DEFAULT -> "Auto"
                else -> effort.displayName
            }
        }
    }
    val selectedEffortIndex = remember(effortOptions, subagentSettings.reasoningEffort) {
        val current = subagentSettings.resolvedReasoningEffort
        effortOptions.indexOf(current).takeIf { it >= 0 } ?: 0
    }

    var showModelSheet by remember { mutableStateOf(false) }
    var inspectedProfile by remember { mutableStateOf<SubagentProfile?>(null) }
    var editorDraft by remember { mutableStateOf<CustomSubagentDraft?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }

    val onToggleSubagent: (Boolean) -> Unit = { checked ->
        val targetPrefs = agentPrefs
        if (targetPrefs != null && targetPrefs.edit().putBoolean(Prefs.Keys.AGENT_SUBAGENT_ENABLED, checked).commit()) {
            subagentEnabled = checked
            Prefs.reconcileAgentPreferences(EtaApp.serviceInstance)
            scope.launch {
                RuntimeConfigRepository.syncToRemotePreferences(EtaApp.serviceInstance)
            }
        }
    }
    val onEffortChange: (Int) -> Unit = { index ->
        val chosen = effortOptions.getOrNull(index)
        scope.launch {
            RuntimeConfigRepository.setSubagentSettings(
                subagentSettings.copy(reasoningEffort = chosen?.wireValue),
            )
        }
    }
    val onSaveDraft: (CustomSubagentDraft) -> Unit = { activeDraft ->
        scope.launch {
            val tools = activeDraft.toolsText
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val saved = SubagentProfileCatalog.saveCustomProfile(
                        name = activeDraft.name,
                        description = activeDraft.description,
                        tools = tools,
                        prompt = activeDraft.prompt,
                    )
                    val oldName = activeDraft.originalName
                    if (oldName != null && oldName != saved.name) {
                        SubagentProfileCatalog.deleteCustomProfile(oldName)
                    }
                    saved
                }
            }
            result.fold(
                onSuccess = {
                    editorDraft = null
                    editorError = null
                    reloadCustomProfiles()
                },
                onFailure = { error ->
                    editorError = error.message ?: "保存失败"
                },
            )
        }
    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = "Subagent",
                onBack = onBack,
                actions = {
                    CouiTopAppBarIconButton(
                        icon = CouiIcons.Outlined.Add,
                        contentDescription = "新建 Subagent",
                        onClick = {
                            editorError = null
                            editorDraft = CustomSubagentDraft()
                        },
                    )
                },
                overlay = {
                    val dark = CouiTheme.isDark
                    Dialog(
                        visible = showModelSheet,
                        onDismissRequest = { showModelSheet = false },
                        dark = dark,
                        title = "Subagent 模型",
                        confirmAction = CouiDialogAction(
                            text = stringResource(R.string.action_close),
                            onClick = { showModelSheet = false },
                        ),
                        placement = CouiDialogPlacement.Bottom,
                        material = rememberCouiDialogMaterial(dark),
                        backdrop = rememberCouiWindowBackdrop(),
                    ) {
                        OptionalModelPickerContent(
                            pickerState = pickerState,
                            selectedSecondaryModelId = resolvedSecondary?.model?.id,
                            followTitle = "跟随主模型",
                            followSubtitle = primaryModelName?.let { "当前：$it" } ?: "默认继承会话正在使用的主模型",
                            onSelectModel = { modelId ->
                                showModelSheet = false
                                scope.launch {
                                    RuntimeConfigRepository.setSubagentSettings(
                                        subagentSettings.copy(modelId = modelId),
                                    )
                                }
                            },
                        )
                    }

                    val currentProfile = inspectedProfile
                    Dialog(
                        visible = currentProfile != null,
                        onDismissRequest = { inspectedProfile = null },
                        dark = dark,
                        title = currentProfile?.name.orEmpty(),
                        confirmAction = CouiDialogAction(
                            text = stringResource(R.string.action_close),
                            onClick = { inspectedProfile = null },
                        ),
                        placement = CouiDialogPlacement.Bottom,
                        material = rememberCouiDialogMaterial(dark),
                        backdrop = rememberCouiWindowBackdrop(),
                    ) {
                        if (currentProfile != null) {
                            SubagentProfileDetailContent(
                                profile = currentProfile,
                                onEdit = if (!currentProfile.builtin) {
                                    {
                                        val toolsText = currentProfile.explicitTools
                                            ?.takeUnless { it == setOf("*") }
                                            ?.joinToString(", ")
                                            .orEmpty()
                                        inspectedProfile = null
                                        editorError = null
                                        editorDraft = CustomSubagentDraft(
                                            originalName = currentProfile.name,
                                            name = currentProfile.name,
                                            description = currentProfile.summary,
                                            toolsText = toolsText,
                                            prompt = currentProfile.roleInstruction,
                                        )
                                    }
                                } else {
                                    null
                                },
                                onDelete = if (!currentProfile.builtin) {
                                    {
                                        val targetName = currentProfile.name
                                        inspectedProfile = null
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                SubagentProfileCatalog.deleteCustomProfile(targetName)
                                            }
                                            reloadCustomProfiles()
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }

                    val activeDraft = editorDraft
                    CouiConfirmDialog(
                        visible = activeDraft != null,
                        title = if (activeDraft?.originalName != null) "编辑 Subagent" else "新建 Subagent",
                        message = null,
                        confirmText = "保存",
                        onConfirm = { if (activeDraft != null) onSaveDraft(activeDraft) },
                        onDismissRequest = { editorDraft = null },
                    ) {
                        if (activeDraft != null) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                CouiTextField(
                                    value = activeDraft.name,
                                    onValueChange = { editorDraft = activeDraft.copy(name = it) },
                                    label = "名称 (kebab-case，如 bill-analyzer)",
                                    singleLine = true,
                                )
                                CouiTextField(
                                    value = activeDraft.description,
                                    onValueChange = { editorDraft = activeDraft.copy(description = it) },
                                    label = "简介 (Description)",
                                    singleLine = true,
                                )
                                CouiTextField(
                                    value = activeDraft.toolsText,
                                    onValueChange = { editorDraft = activeDraft.copy(toolsText = it) },
                                    label = "可用工具 (逗号分隔，留空默认允许全部非 GUI 工具)",
                                    singleLine = true,
                                )
                                CouiTextField(
                                    value = activeDraft.prompt,
                                    onValueChange = { editorDraft = activeDraft.copy(prompt = it) },
                                    label = "角色指令 (System Prompt)",
                                    singleLine = false,
                                    minLines = 4,
                                    maxLines = 8,
                                )
                                editorError?.let { err ->
                                    Text(
                                        text = err,
                                        style = MiuixTheme.textStyles.footnote1,
                                        color = StatusError,
                                    )
                                }
                            }
                        }
                    }
                },
            ) {
                Group(title = "通用与模型") {
                    CouiSwitchPreference(
                        title = "启用 Subagent",
                        checked = subagentEnabled,
                        enabled = agentPrefs != null,
                        onCheckedChange = onToggleSubagent,
                    )
                    Divider()
                    CouiPreference(
                        title = "Subagent 模型",
                        summary = secondarySummary,
                        enabled = subagentEnabled,
                        onClick = { showModelSheet = true },
                    )
                    if (effortOptions.size > 1) {
                        Divider()
                        CouiDropdownPreference(
                            title = "思考强度",
                            items = effortLabels,
                            selectedIndex = selectedEffortIndex,
                            enabled = subagentEnabled,
                            onSelectedIndexChange = onEffortChange,
                        )
                    }
                }

                SubagentAdvancedSettings(subagentSettings, pickerState, subagentEnabled)
                Group(title = "Built-in Subagents") {
                    SubagentProfile.BUILTIN_PROFILES.forEachIndexed { index, profile ->
                        CouiPreference(
                            title = profile.name,
                            summary = profile.summary,
                            onClick = { inspectedProfile = profile },
                        )
                        if (index < SubagentProfile.BUILTIN_PROFILES.lastIndex) {
                            Divider()
                        }
                    }
                }

                Group(title = "Custom Subagents") {
                    if (customProfiles.isEmpty()) {
                        ListEmptyState(
                            title = "暂无自定义 Subagent",
                            summary = "可在对话中直接让 Eta 创建专属 Subagent（保存在 agents/<name>.md），或点击右上角 + 手动新建",
                            icon = Icons.Rounded.Extension,
                            action = {
                                CouiButton(
                                    text = "新建 Subagent",
                                    role = CouiButtonRole.Primary,
                                    onClick = {
                                        editorError = null
                                        editorDraft = CustomSubagentDraft()
                                    },
                                )
                            },
                        )
                    } else {
                        customProfiles.forEachIndexed { index, profile ->
                            CouiPreference(
                                title = profile.name,
                                summary = profile.summary,
                                onClick = { inspectedProfile = profile },
                            )
                            if (index < customProfiles.lastIndex) {
                                Divider()
                            }
                        }
                    }
                }

                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = "Subagent",
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = {
                            editorError = null
                            editorDraft = CustomSubagentDraft()
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "新建 Subagent",
                        )
                    }
                },
                overlay = {
                    OverlayBottomSheet(
                        show = showModelSheet,
                        title = "Subagent 模型",
                        onDismissRequest = { showModelSheet = false },
                        insideMargin = DpSize(0.dp, 0.dp),
                    ) {
                        OptionalModelPickerContent(
                            pickerState = pickerState,
                            selectedSecondaryModelId = resolvedSecondary?.model?.id,
                            followTitle = "跟随主模型",
                            followSubtitle = primaryModelName?.let { "当前：$it" } ?: "默认继承会话正在使用的主模型",
                            onSelectModel = { modelId ->
                                showModelSheet = false
                                scope.launch {
                                    RuntimeConfigRepository.setSubagentSettings(
                                        subagentSettings.copy(modelId = modelId),
                                    )
                                }
                            },
                        )
                    }

                    val currentProfile = inspectedProfile
                    OverlayBottomSheet(
                        show = currentProfile != null,
                        title = currentProfile?.name.orEmpty(),
                        onDismissRequest = { inspectedProfile = null },
                    ) {
                        if (currentProfile != null) {
                            SubagentProfileDetailContent(
                                profile = currentProfile,
                                onEdit = if (!currentProfile.builtin) {
                                    {
                                        val toolsText = currentProfile.explicitTools
                                            ?.takeUnless { it == setOf("*") }
                                            ?.joinToString(", ")
                                            .orEmpty()
                                        inspectedProfile = null
                                        editorError = null
                                        editorDraft = CustomSubagentDraft(
                                            originalName = currentProfile.name,
                                            name = currentProfile.name,
                                            description = currentProfile.summary,
                                            toolsText = toolsText,
                                            prompt = currentProfile.roleInstruction,
                                        )
                                    }
                                } else {
                                    null
                                },
                                onDelete = if (!currentProfile.builtin) {
                                    {
                                        val targetName = currentProfile.name
                                        inspectedProfile = null
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                SubagentProfileCatalog.deleteCustomProfile(targetName)
                                            }
                                            reloadCustomProfiles()
                                        }
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }

                    val activeDraft = editorDraft
                    WindowDialog(
                        show = activeDraft != null,
                        title = if (activeDraft?.originalName != null) "编辑 Subagent" else "新建 Subagent",
                        onDismissRequest = { editorDraft = null },
                    ) {
                        if (activeDraft != null) {
                            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
                                Column(
                                    verticalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f, fill = false)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    TextField(
                                        value = activeDraft.name,
                                        onValueChange = { editorDraft = activeDraft.copy(name = it) },
                                        label = "名称 (kebab-case，如 bill-analyzer)",
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    TextField(
                                        value = activeDraft.description,
                                        onValueChange = { editorDraft = activeDraft.copy(description = it) },
                                        label = "简介 (Description)",
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    TextField(
                                        value = activeDraft.toolsText,
                                        onValueChange = { editorDraft = activeDraft.copy(toolsText = it) },
                                        label = "可用工具 (逗号分隔，留空默认允许全部非 GUI 工具)",
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    TextField(
                                        value = activeDraft.prompt,
                                        onValueChange = { editorDraft = activeDraft.copy(prompt = it) },
                                        label = "角色指令 (System Prompt)",
                                        minLines = 4,
                                        maxLines = 8,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    editorError?.let { err ->
                                        Text(
                                            text = err,
                                            style = MiuixTheme.textStyles.footnote1,
                                            color = StatusError,
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                MiuixDialogActions(
                                    confirmText = "保存",
                                    onCancel = { editorDraft = null },
                                    onConfirm = { onSaveDraft(activeDraft) },
                                )
                            }
                        }
                    }
                },
            ) {
                item(key = "config_group") {
                    SmallTitle("通用与模型")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        SwitchPreference(
                            title = "启用 Subagent",
                            checked = subagentEnabled,
                            enabled = agentPrefs != null,
                            onCheckedChange = onToggleSubagent,
                        )
                        ArrowPreference(
                            title = "Subagent 模型",
                            summary = secondarySummary,
                            enabled = subagentEnabled,
                            onClick = { showModelSheet = true },
                        )
                        if (effortOptions.size > 1) {
                            OverlayDropdownPreference(
                                title = "思考强度",
                                items = effortLabels,
                                selectedIndex = selectedEffortIndex,
                                enabled = subagentEnabled,
                                onSelectedIndexChange = onEffortChange,
                            )
                        }
                    }
                }

                item(key = "advanced_subagent_settings") {
                    SubagentAdvancedSettings(subagentSettings, pickerState, subagentEnabled)
                }
                item(key = "builtin_group") {
                    SmallTitle("Built-in Subagents")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        SubagentProfile.BUILTIN_PROFILES.forEach { profile ->
                            ArrowPreference(
                                title = profile.name,
                                summary = profile.summary,
                                onClick = { inspectedProfile = profile },
                            )
                        }
                    }
                }

                item(key = "custom_group") {
                    SmallTitle("Custom Subagents")
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        if (customProfiles.isEmpty()) {
                            ListEmptyState(
                                title = "暂无自定义 Subagent",
                                summary = "可在对话中直接让 Eta 创建专属 Subagent（保存在 agents/<name>.md），或点击右上角 + 手动新建",
                                icon = Icons.Rounded.Extension,
                                action = {
                                    TextButton(
                                        text = "新建 Subagent",
                                        onClick = {
                                            editorError = null
                                            editorDraft = CustomSubagentDraft()
                                        },
                                    )
                                },
                            )
                        } else {
                            customProfiles.forEach { profile ->
                                ArrowPreference(
                                    title = profile.name,
                                    summary = profile.summary,
                                    onClick = { inspectedProfile = profile },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubagentProfileDetailContent(
    profile: SubagentProfile,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    val uiStyle = LocalUiStyle.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (uiStyle == AppearanceUiStyle.COUI) {
                    Modifier.padding(horizontal = CouiDialogDefaults.contentPadding, vertical = 8.dp)
                } else {
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(bottom = 24.dp)
                },
            ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = profile.summary,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = if (profile.builtin) profile.whenToUse else profile.roleInstruction,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Tools",
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                profile.toolHighlights.forEach { toolName ->
                    Box(
                        modifier = Modifier
                            .squircleSurface(
                                color = MiuixTheme.colorScheme.surfaceContainer,
                                cornerRadius = 8.dp,
                            )
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = toolName,
                            style = MiuixTheme.textStyles.footnote2.copy(fontFamily = FontFamily.Monospace),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
        if (onEdit != null || onDelete != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (onDelete != null) {
                    if (uiStyle == AppearanceUiStyle.COUI) {
                        CouiButton(
                            text = "删除",
                            onClick = onDelete,
                            role = CouiButtonRole.Destructive,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        TextButton(
                            text = "删除",
                            onClick = onDelete,
                            colors = ButtonDefaults.textButtonColors(
                                textColor = StatusError,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (onEdit != null) {
                    if (uiStyle == AppearanceUiStyle.COUI) {
                        CouiButton(
                            text = "编辑",
                            onClick = onEdit,
                            role = CouiButtonRole.Primary,
                            modifier = Modifier.weight(1f),
                        )
                    } else {
                        TextButton(
                            text = "编辑",
                            onClick = onEdit,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

private enum class SubagentPolicyEditor { Parallel, Rounds, Failures, Authorized }

@Composable
private fun SubagentAdvancedSettings(
    settings: SubagentSettings,
    pickerState: AgentModelPickerUiState,
    enabled: Boolean,
) {
    val scope = rememberCoroutineScope()
    var editor by remember { mutableStateOf<SubagentPolicyEditor?>(null) }
    var number by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val models = pickerState.providerGroups.flatMap { it.models }
    val idToApiId = models.associate { it.id to it.modelId }
    fun canonical(id: String): String = idToApiId[id] ?: id
    fun persist(next: SubagentSettings, close: Boolean = false) {
        if (saving) return
        saving = true
        error = null
        scope.launch {
            try {
                RuntimeConfigRepository.setSubagentSettings(next)
                if (close) editor = null
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = failure.message ?: "无法保存子智能体配置，请重试"
            } finally {
                saving = false
            }
        }
    }
    val body: @Composable () -> Unit = {
        listOf(
            Triple(SubagentPolicyEditor.Parallel, "并行上限", settings.maxParallel),
            Triple(SubagentPolicyEditor.Rounds, "单次轮次上限", settings.maxRounds),
            Triple(SubagentPolicyEditor.Failures, "连续失败中止（0 为关闭）", settings.failureAbortThreshold),
        ).forEach { (target, title, current) ->
            SubagentPolicyPreference(title, current.toString(), enabled && !saving) {
                number = current.toString()
                error = null
                editor = target
            }
        }
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            CouiSwitchPreference(
                title = "允许主 Agent 为子智能体选择模型",
                summary = "仅从授权模型中选择；仅影响新会话",
                checked = settings.allowModelSelection,
                enabled = enabled && !saving,
                onCheckedChange = { persist(settings.copy(allowModelSelection = it)) },
            )
        } else {
            SwitchPreference(
                title = "允许主 Agent 为子智能体选择模型",
                summary = "仅从授权模型中选择；仅影响新会话",
                checked = settings.allowModelSelection,
                enabled = enabled && !saving,
                onCheckedChange = { persist(settings.copy(allowModelSelection = it)) },
            )
        }
        SubagentPolicyPreference(
            "授权模型",
            if (settings.effectiveGrants.isEmpty()) "未勾选：不限制已启用模型"
            else "已授权 ${settings.effectiveGrants.size} 个模型",
            enabled && settings.allowModelSelection && !saving,
        ) {
            selected = settings.effectiveGrants.map { canonical(it.modelId) }.toSet()
            query = ""
            error = null
            editor = SubagentPolicyEditor.Authorized
        }
        error?.let { Text(it, color = StatusError, modifier = Modifier.padding(16.dp)) }
    }
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        Group(title = "运行限制与模型授权") { body() }
    } else {
        SmallTitle("运行限制与模型授权")
        Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) { body() }
    }
    val active = editor
    if (active != null) {
        val authorized = active == SubagentPolicyEditor.Authorized
        val range = when (active) {
            SubagentPolicyEditor.Parallel -> 1..64
            SubagentPolicyEditor.Rounds -> 1..200
            else -> 0..50
        }
        val title = when (active) {
            SubagentPolicyEditor.Parallel -> "并行上限"
            SubagentPolicyEditor.Rounds -> "单次轮次上限"
            SubagentPolicyEditor.Failures -> "连续失败中止"
            SubagentPolicyEditor.Authorized -> "授权给主 Agent 的模型"
        }
        val confirm: () -> Unit = {
            val next = if (authorized) {
                // Retain existing per-model effort restrictions. Toggling a model
                // must not silently expand an existing authorization grant.
                val previous = settings.effectiveGrants.associateBy { canonical(it.modelId) }
                settings.copy(
                    allowedModelIds = selected.toList(),
                    allowedGrants = selected.map { id ->
                        SubagentModelGrant(id, previous[id]?.allowedEfforts.orEmpty())
                    },
                )
            } else {
                val value = number.toIntOrNull()
                if (value == null || value !in range) null else when (active) {
                    SubagentPolicyEditor.Parallel -> settings.copy(maxParallel = value)
                    SubagentPolicyEditor.Rounds -> settings.copy(maxRounds = value)
                    else -> settings.copy(failureAbortThreshold = value)
                }
            }
            if (next != null) persist(next, close = true)
        }
        val content: @Composable () -> Unit = {
            if (authorized) {
                if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                    CouiTextField(value = query, onValueChange = { query = it }, label = "搜索模型或服务商", singleLine = true)
                    CouiButton(text = "清空授权（不限制）", enabled = !saving, onClick = { selected = emptySet() })
                } else {
                    TextField(value = query, onValueChange = { query = it }, label = "搜索模型或服务商", singleLine = true)
                    TextButton(text = "清空授权（不限制）", enabled = !saving, onClick = { selected = emptySet() })
                }
                val groups = pickerState.filterGroups(query)
                groups.forEach { group ->
                    group.models.forEach { model ->
                        val checked = model.modelId in selected
                        val toggle: (Boolean) -> Unit = { value ->
                            selected = if (value) selected + model.modelId else selected - model.modelId
                        }
                        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                            CouiSwitchPreference(title = model.displayName,
                                summary = "${group.providerName} · ${model.modelId}",
                                checked = checked, enabled = !saving, onCheckedChange = toggle)
                        } else {
                            SwitchPreference(title = model.displayName,
                                summary = "${group.providerName} · ${model.modelId}",
                                checked = checked, enabled = !saving, onCheckedChange = toggle)
                        }
                    }
                }
                val validIds = models.map { it.modelId }.toSet()
                val invalid = selected - validIds
                if (invalid.isNotEmpty()) {
                    TextButton(text = "清理 ${invalid.size} 个失效授权", enabled = !saving,
                        onClick = { selected = selected - invalid })
                }
            } else {
                val label = "允许范围：${range.first}–${range.last}"
                if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
                    CouiTextField(value = number, onValueChange = { number = it.filter(Char::isDigit).take(3) },
                        label = label, singleLine = true)
                } else {
                    TextField(value = number, onValueChange = { number = it.filter(Char::isDigit).take(3) },
                        label = label, singleLine = true)
                }
            }
            error?.let { Text(it, color = StatusError) }
        }
        val canSave = !saving && (authorized || number.toIntOrNull()?.let { it in range } == true)
        val dismiss: () -> Unit = { if (!saving) editor = null }
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            CouiConfirmDialog(visible = true, title = title, message = null,
                confirmText = "保存", confirmEnabled = canSave, cancelEnabled = !saving,
                onConfirm = confirm, onDismissRequest = dismiss) { content() }
        } else {
            WindowDialog(show = true, title = title, onDismissRequest = dismiss) {
                val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.7f
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
                    Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) { content() }
                    MiuixDialogActions(confirmText = "保存", confirmEnabled = canSave,
                        cancelEnabled = !saving, onCancel = dismiss, onConfirm = confirm)
                }
            }
        }
    }
}

@Composable
private fun SubagentPolicyPreference(title: String, summary: String, enabled: Boolean, onClick: () -> Unit) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiPreference(title = title, summary = summary, enabled = enabled, onClick = onClick)
    } else {
        ArrowPreference(title = title, summary = summary, enabled = enabled, onClick = onClick)
    }
}
