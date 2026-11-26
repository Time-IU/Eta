package io.github.mangi.eta.ui.screens.backup

import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.Icon
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import io.github.mangi.eta.ui.components.coui.CouiTextField
import io.github.mangi.eta.ui.components.coui.CouiSwitchPreference
import io.github.mangi.eta.data.repository.AutoBackupRunner
import io.github.mangi.eta.data.repository.AutoBackupResult
import io.github.mangi.eta.data.model.Settings
import io.github.mangi.eta.data.model.AutoBackupKeepCount
import io.github.mangi.eta.data.model.AutoBackupFormat
import io.github.mangi.eta.data.model.AutoBackupInterval
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.agent.automation.AutoBackupAlarm
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.Icons
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.Alignment
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.Divider
import com.itos.coui.layout.Group
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import io.github.mangi.eta.R
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.safeStackTrace
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.repository.EtaBackupSummary
import io.github.mangi.eta.data.repository.EtaUserExtensionsBackup
import io.github.mangi.eta.data.repository.UserExtensionInventory
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiPageBottomSpacer
import io.github.mangi.eta.ui.components.coui.CouiPreference
import io.github.mangi.eta.ui.components.coui.CouiScaffoldPage
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.preference.ArrowPreference
import io.github.mangi.eta.ui.components.EtaWindowDialog as WindowDialog

@Composable
internal fun DataBackupScreen(
    context: Context,
    onBack: () -> Unit,
    onExport: suspend (OutputStream) -> EtaBackupSummary,
    onImport: suspend (InputStream) -> EtaBackupSummary,
) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var showImportDialog by remember { mutableStateOf(false) }
    var extensionInventory by remember { mutableStateOf<UserExtensionInventory?>(null) }
    var showExtensionDialog by remember { mutableStateOf(false) }
    var extensionDialogIsImport by remember { mutableStateOf(false) }
    var extensionImportUri by remember { mutableStateOf<Uri?>(null) }
    var extensionExportSelection by remember { mutableStateOf<Pair<Set<String>, Set<String>>?>(null) }
    var selectedExtensionSkills by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedExtensionSubagents by remember { mutableStateOf<Set<String>>(emptySet()) }

    val settings by SettingsDataStore.settingsFlow().collectAsState(initial = Settings())
    var autoBusy by remember { mutableStateOf(false) }
    var autoNote by remember { mutableStateOf<String?>(null) }
    // 「最近备份」必须以磁盘上的真实文件为准：记录里的文件名可能已被清理掉或换过目录，
    // 直接显示记录会指向一个根本不存在的文件。
    var lastBackupName by remember { mutableStateOf("") }
    LaunchedEffect(settings.autoBackupDirectory, settings.autoBackupLastFileName, settings.autoBackupLastAt) {
        lastBackupName = withContext(Dispatchers.IO) {
            AutoBackupRunner.resolveLastBackup(
                recordedName = settings.autoBackupLastFileName,
                customDirectory = settings.autoBackupDirectory,
            )?.name.orEmpty()
        }
    }
    var showDirDialog by remember { mutableStateOf(false) }
    var dirInput by remember { mutableStateOf("") }
    var showIntervalDialog by remember { mutableStateOf(false) }
    var intervalInput by remember { mutableStateOf("") }
    var showKeepDialog by remember { mutableStateOf(false) }
    var keepInput by remember { mutableStateOf("") }

    fun showFailure(throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        AndroidAgentLogger.error("Agent backup UI failed: type=${throwable.safeLogType()}\n${throwable.safeStackTrace()}")
        Toast.makeText(
            context,
            throwable.message ?: context.getString(R.string.data_backup_failed),
            Toast.LENGTH_LONG,
        ).show()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            try {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(context.getString(R.string.data_backup_file_open_failed))
                val summary = output.use { onExport(it) }
                Toast.makeText(
                    context,
                    context.getString(
                        R.string.data_backup_exported,
                        summary.conversationCount,
                        summary.providerCount,
                    ),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (throwable: Throwable) {
                showFailure(throwable)
            } finally {
                busy = false
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            pendingImportUri = uri
            showImportDialog = true
        }
    }

    val extensionsExportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val selection = extensionExportSelection
        if (uri != null && selection != null) scope.launch {
            busy = true
            try {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error(context.getString(R.string.data_backup_file_open_failed))
                withContext(Dispatchers.IO) {
                    output.use { EtaUserExtensionsBackup.export(context, it, selection.first, selection.second) }
                }
                Toast.makeText(context, context.getString(R.string.data_backup_extensions_exported,
                    selection.first.size, selection.second.size), Toast.LENGTH_SHORT).show()
            } catch (failure: Throwable) { showFailure(failure) }
            finally { busy = false; extensionExportSelection = null }
        } else {
            extensionExportSelection = null
        }
    }
    val extensionsImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error(context.getString(R.string.data_backup_file_open_failed))
                val inventory = withContext(Dispatchers.IO) { input.use { EtaUserExtensionsBackup.inspect(context, it) } }
                extensionImportUri = uri
                extensionInventory = inventory
                selectedExtensionSkills = inventory.skills.filterNot { it.conflict }.mapTo(linkedSetOf()) { it.id }
                selectedExtensionSubagents = inventory.subagents.filterNot { it.conflict }.mapTo(linkedSetOf()) { it.id }
                extensionDialogIsImport = true
                showExtensionDialog = true
            } catch (failure: Throwable) { showFailure(failure) }
            finally { busy = false }
        }
    }

    val extensionBackupContent: @Composable () -> Unit = {
        val exportAction: () -> Unit = {
            scope.launch {
                busy = true
                try {
                    val available = withContext(Dispatchers.IO) { EtaUserExtensionsBackup.available(context) }
                    extensionInventory = available
                    selectedExtensionSkills = available.skills.mapTo(linkedSetOf()) { it.id }
                    selectedExtensionSubagents = available.subagents.mapTo(linkedSetOf()) { it.id }
                    extensionDialogIsImport = false
                    showExtensionDialog = true
                } catch (failure: Throwable) { showFailure(failure) }
                finally { busy = false }
            }
        }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiPreference(
                    title = stringResource(R.string.data_backup_extensions_export),
                    summary = stringResource(R.string.data_backup_extensions_export_summary),
                    enabled = !busy,
                    onClick = exportAction,
                )
                Divider()
                CouiPreference(
                    title = stringResource(R.string.data_backup_extensions_import),
                    summary = stringResource(R.string.data_backup_extensions_import_summary),
                    enabled = !busy,
                    onClick = { extensionsImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                )
            }
            AppearanceUiStyle.MIUIX -> {
                ArrowPreference(
                    title = stringResource(R.string.data_backup_extensions_export),
                    summary = stringResource(R.string.data_backup_extensions_export_summary),
                    enabled = !busy,
                    onClick = exportAction,
                )
                ArrowPreference(
                    title = stringResource(R.string.data_backup_extensions_import),
                    summary = stringResource(R.string.data_backup_extensions_import_summary),
                    enabled = !busy,
                    onClick = { extensionsImportLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed")) },
                )
            }
        }
    }

    val autoBackupContent: @Composable () -> Unit = {

                BackupSwitchPreference(
                    title = stringResource(R.string.data_backup_auto_enable),
                    summary = stringResource(R.string.data_backup_auto_enable_summary),
                    checked = settings.autoBackupEnabled,
                    onCheckedChange = { value ->
                        scope.launch {
                            try {
                                SettingsDataStore.updateSettings { it.copy(autoBackupEnabled = value) }
                                withContext(Dispatchers.IO) {
                                    if (value) AutoBackupAlarm.refresh(context) else AutoBackupAlarm.cancel(context)
                                }
                            } catch (failure: Throwable) { showFailure(failure) }
                        }
                    },
                )
                BackupDivider()
                BackupPreference(
                    title = stringResource(R.string.data_backup_auto_interval),
                    summary = intervalLabel(context, settings.autoBackupIntervalMinutes),
                    enabled = settings.autoBackupEnabled,
                    onClick = {
                        intervalInput = AutoBackupInterval
                            .effectiveMinutes(settings.autoBackupIntervalMinutes, settings.autoBackupIntervalHours)
                            .toString()
                        showIntervalDialog = true
                    },
                )
                BackupDivider()
                BackupPreference(
                    title = stringResource(R.string.data_backup_auto_keep),
                    summary = stringResource(R.string.data_backup_auto_keep_value, settings.autoBackupKeepCount),
                    enabled = settings.autoBackupEnabled,
                    onClick = {
                        keepInput = settings.autoBackupKeepCount.toString()
                        showKeepDialog = true
                    },
                )
                BackupDivider()
                BackupPreference(
                    title = stringResource(R.string.data_backup_auto_format),
                    summary = if (AutoBackupFormat.isFull(settings.autoBackupFormat)) {
                        stringResource(R.string.data_backup_auto_format_full_summary)
                    } else {
                        stringResource(R.string.data_backup_auto_format_json_summary)
                    },
                    enabled = settings.autoBackupEnabled,
                    startAction = {
                        BackupIcon(icon = Icons.Rounded.Description, loading = false)
                    },
                    onClick = {
                        val next = if (AutoBackupFormat.isFull(settings.autoBackupFormat)) {
                            AutoBackupFormat.JSON
                        } else {
                            AutoBackupFormat.FULL
                        }
                        scope.launch {
                            try {
                            SettingsDataStore.updateSettings { it.copy(autoBackupFormat = next) }
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.data_backup_auto_format_saved,
                                    if (AutoBackupFormat.isFull(next)) {
                                        context.getString(R.string.data_backup_auto_format_full)
                                    } else {
                                        context.getString(R.string.data_backup_auto_format_json)
                                    },
                                ),
                                Toast.LENGTH_SHORT,
                            ).show()

                            } catch (failure: Throwable) { showFailure(failure) }
                        }
                    },
                )
                BackupDivider()
                BackupPreference(
                    title = stringResource(R.string.data_backup_auto_run_now),
                    summary = autoNote ?: when {
                        settings.autoBackupLastError.isNotBlank() -> stringResource(
                            R.string.data_backup_auto_error,
                            settings.autoBackupLastError,
                        )
                        lastBackupName.isNotBlank() -> stringResource(R.string.data_backup_auto_last_file, lastBackupName)
                        else -> stringResource(R.string.data_backup_auto_never)
                    },
                    enabled = !autoBusy && !busy,
                    startAction = {
                        BackupIcon(icon = Icons.Rounded.Download, loading = autoBusy)
                    },
                    onClick = {
                        if (!autoBusy) {
                            autoBusy = true
                            scope.launch {
                                try {
                                val result = AutoBackupRunner.maybeRun(context, force = true)
                                val text = when (result) {
                                    is AutoBackupResult.Done -> context.getString(
                                        R.string.data_backup_auto_done,
                                        result.fileName,
                                        result.conversationCount,
                                        result.kept,
                                    )
                                    is AutoBackupResult.Failed -> context.getString(
                                        R.string.data_backup_auto_failed,
                                        result.message,
                                    )
                                    is AutoBackupResult.Skipped -> context.getString(R.string.data_backup_auto_skipped)
                                }
                                AutoBackupAlarm.refresh(context)
                                autoBusy = false
                                if (result is AutoBackupResult.Done) lastBackupName = result.fileName
                                autoNote = text
                                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                                } catch (failure: Throwable) {
                                    showFailure(failure)
                                } finally {
                                    autoBusy = false
                                }
                            }
                        }
                    },
                )
                BackupDivider()
                BackupPreference(
                    title = stringResource(R.string.data_backup_auto_dir),
                    summary = AutoBackupRunner.directory(settings.autoBackupDirectory).absolutePath + " · " +
                        stringResource(
                            if (settings.autoBackupDirectory.isBlank()) {
                                R.string.data_backup_dir_default
                            } else {
                                R.string.data_backup_dir_custom
                            },
                        ),
                    onClick = {
                        dirInput = settings.autoBackupDirectory
                        showDirDialog = true
                    },
                )

    }

    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> {
            CouiScaffoldPage(
                title = stringResource(R.string.data_backup_title),
                onBack = onBack,
            ) {
                Group {
                    CouiPreference(
                        title = stringResource(R.string.data_backup_warning_title),
                        summary = stringResource(R.string.data_backup_warning_summary),
                        showChevron = false,
                    )
                }
                Group(title = stringResource(R.string.data_backup_actions)) {
                    CouiPreference(
                        title = stringResource(R.string.data_backup_export),
                        summary = if (busy) {
                            stringResource(R.string.data_backup_working)
                        } else {
                            stringResource(R.string.data_backup_export_summary)
                        },
                        enabled = !busy,
                        trailing = if (busy) {
                            { InfiniteProgressIndicator(size = 20.dp) }
                        } else {
                            null
                        },
                        onClick = { exportLauncher.launch(defaultBackupFileName()) },
                    )
                    Divider()
                    CouiPreference(
                        title = stringResource(R.string.data_backup_import),
                        summary = stringResource(R.string.data_backup_import_summary),
                        enabled = !busy,
                        onClick = {
                            importLauncher.launch(arrayOf("application/json", "text/plain"))
                        },
                    )
                }
                Group(title = stringResource(R.string.data_backup_extensions_title)) { extensionBackupContent() }
                Group(title = stringResource(R.string.data_backup_auto)) { autoBackupContent() }
                CouiPageBottomSpacer()
            }
        }

        AppearanceUiStyle.MIUIX -> {
            MiuixScaffoldPage(
                title = stringResource(R.string.data_backup_title),
                onBack = onBack,
            ) {
                item(key = "warning") {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(top = 8.dp, bottom = 12.dp),
                    ) {
                        BasicComponent(
                            title = stringResource(R.string.data_backup_warning_title),
                            summary = stringResource(R.string.data_backup_warning_summary),
                        )
                    }
                }
                item(key = "actions") {
                    SmallTitle(stringResource(R.string.data_backup_actions))
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.data_backup_export),
                            summary = if (busy) {
                                stringResource(R.string.data_backup_working)
                            } else {
                                stringResource(R.string.data_backup_export_summary)
                            },
                            enabled = !busy,
                            endActions = {
                                if (busy) {
                                    InfiniteProgressIndicator(size = 20.dp)
                                }
                            },
                            onClick = {
                                exportLauncher.launch(defaultBackupFileName())
                            },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.data_backup_import),
                            summary = stringResource(R.string.data_backup_import_summary),
                            enabled = !busy,
                            onClick = {
                                importLauncher.launch(arrayOf("application/json", "text/plain"))
                            },
                        )
                    }
                }
                item(key = "extensions_backup") {
                    SmallTitle(stringResource(R.string.data_backup_extensions_title))
                    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) { extensionBackupContent() }
                }
                item(key = "auto_backup") {
                    SmallTitle(stringResource(R.string.data_backup_auto))
                    Card(modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) { autoBackupContent() }
                }
            }
        }
    }

    if (showDirDialog) {
        BackupInputDialog(
            title = stringResource(R.string.data_backup_dir_dialog_title),
            summary = stringResource(R.string.data_backup_dir_dialog_summary, AutoBackupRunner.defaultDirectory().absolutePath),
            label = stringResource(R.string.data_backup_dir_dialog_label),
            value = dirInput, onValueChange = { dirInput = it },
            onDismiss = { showDirDialog = false },
            onConfirm = {
                scope.launch {
                    try {
                        SettingsDataStore.updateSettings { it.copy(autoBackupDirectory = dirInput.trim()) }
                        AutoBackupAlarm.refresh(context)
                        showDirDialog = false
                    } catch (failure: Throwable) { showFailure(failure) }
                }
            },
        )
    }
    if (showIntervalDialog) {
        val valid = intervalInput.trim().toIntOrNull()?.let {
            it in AutoBackupInterval.MIN_MINUTES..AutoBackupInterval.MAX_MINUTES
        } == true
        BackupInputDialog(
            title = stringResource(R.string.data_backup_interval_dialog_title),
            summary = stringResource(R.string.data_backup_interval_dialog_summary,
                AutoBackupInterval.MIN_MINUTES, AutoBackupInterval.MAX_MINUTES),
            label = stringResource(R.string.data_backup_interval_dialog_label),
            value = intervalInput, onValueChange = { intervalInput = it.filter(Char::isDigit) },
            confirmEnabled = valid,
            onDismiss = { showIntervalDialog = false },
            onConfirm = {
                val minutes = intervalInput.trim().toIntOrNull()
                if (minutes != null && valid) scope.launch {
                    try {
                        SettingsDataStore.updateSettings { it.copy(autoBackupIntervalMinutes = minutes) }
                        AutoBackupAlarm.refresh(context)
                        showIntervalDialog = false
                    } catch (failure: Throwable) { showFailure(failure) }
                }
            },
        )
    }
    if (showKeepDialog) {
        val valid = keepInput.trim().toIntOrNull()?.let { it in AutoBackupKeepCount.MIN..AutoBackupKeepCount.MAX } == true
        BackupInputDialog(
            title = stringResource(R.string.data_backup_keep_dialog_title),
            summary = stringResource(R.string.data_backup_keep_dialog_summary, AutoBackupKeepCount.MIN, AutoBackupKeepCount.MAX),
            label = stringResource(R.string.data_backup_keep_dialog_label),
            value = keepInput, onValueChange = { keepInput = it.filter(Char::isDigit) },
            confirmEnabled = valid,
            onDismiss = { showKeepDialog = false },
            onConfirm = {
                val count = keepInput.trim().toIntOrNull()
                if (count != null && valid) scope.launch {
                    try {
                        SettingsDataStore.updateSettings { it.copy(autoBackupKeepCount = count) }
                        showKeepDialog = false
                    } catch (failure: Throwable) { showFailure(failure) }
                }
            },
        )
    }

    UserExtensionsSelectionDialog(
        visible = showExtensionDialog,
        inventory = extensionInventory,
        selectedSkills = selectedExtensionSkills,
        selectedSubagents = selectedExtensionSubagents,
        busy = busy,
        onToggleSkill = { id -> selectedExtensionSkills = selectedExtensionSkills.toMutableSet().apply {
            if (!add(id)) remove(id)
        } },
        onToggleSubagent = { id -> selectedExtensionSubagents = selectedExtensionSubagents.toMutableSet().apply {
            if (!add(id)) remove(id)
        } },
        onDismiss = {
            showExtensionDialog = false
            extensionImportUri = null
        },
        onConfirm = {
            val skills = selectedExtensionSkills.toSet()
            val subagents = selectedExtensionSubagents.toSet()
            if (!extensionDialogIsImport) {
                extensionDialogIsImport = false
                extensionExportSelection = skills to subagents
                showExtensionDialog = false
                extensionsExportLauncher.launch(defaultExtensionsFileName())
            } else {
                val uri = extensionImportUri
                if (uri != null) scope.launch {
                    busy = true
                    try {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error(context.getString(R.string.data_backup_file_open_failed))
                        val result = withContext(Dispatchers.IO) {
                            input.use { EtaUserExtensionsBackup.importSelected(context, it, skills, subagents) }
                        }
                        val skipped = result.skippedConflicts.size
                        Toast.makeText(context, context.getString(
                            R.string.data_backup_extensions_imported,
                            result.installedSkills, result.installedSubagents, skipped,
                        ), Toast.LENGTH_LONG).show()
                        showExtensionDialog = false
                        extensionImportUri = null
                    } catch (failure: Throwable) { showFailure(failure) }
                    finally { busy = false }
                }
            }
        },
    )

    if (showImportDialog) {
        val confirmText = if (busy) {
            stringResource(R.string.data_backup_working)
        } else {
            stringResource(R.string.action_import)
        }
        val onDismissImport: () -> Unit = {
            if (!busy) {
                showImportDialog = false
                pendingImportUri = null
            }
        }
        val onConfirmImport: () -> Unit = {
            val uri = pendingImportUri
            if (uri != null) {
                showImportDialog = false
                scope.launch {
                    busy = true
                    try {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error(context.getString(R.string.data_backup_file_open_failed))
                        val summary = input.use { onImport(it) }
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.data_backup_imported,
                                summary.conversationCount,
                                summary.providerCount,
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } catch (throwable: Throwable) {
                        showFailure(throwable)
                    } finally {
                        pendingImportUri = null
                        busy = false
                    }
                }
            }
        }
        when (LocalUiStyle.current) {
            AppearanceUiStyle.COUI -> {
                CouiConfirmDialog(
                    visible = true,
                    title = stringResource(R.string.data_backup_import_confirm_title),
                    message = stringResource(R.string.data_backup_import_confirm_summary),
                    confirmText = confirmText,
                    confirmEnabled = !busy,
                    cancelEnabled = !busy,
                    destructive = true,
                    onConfirm = onConfirmImport,
                    onDismissRequest = onDismissImport,
                )
            }
            AppearanceUiStyle.MIUIX -> {
                WindowDialog(
                    show = true,
                    title = stringResource(R.string.data_backup_import_confirm_title),
                    summary = stringResource(R.string.data_backup_import_confirm_summary),
                    onDismissRequest = onDismissImport,
                ) {
                    MiuixDialogActions(
                        confirmText = confirmText,
                        destructive = true,
                        cancelEnabled = !busy,
                        confirmEnabled = !busy,
                        onCancel = onDismissImport,
                        onConfirm = onConfirmImport,
                    )
                }
            }
        }
    }
}

private fun defaultBackupFileName(): String =
    "Eta-backup-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.eta-backup.json"

private fun defaultExtensionsFileName(): String =
    "Eta-extensions-${SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())}.eta-extensions.zip"

@Composable
private fun BackupIcon(icon: ImageVector, loading: Boolean) {
    Box(
        // 与 EtaPreferenceIcon 同宽，正文和分割线起点保持对齐。
        modifier = Modifier.size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            InfiniteProgressIndicator(size = 20.dp)
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MiuixTheme.colorScheme.onBackground,
            )
        }
    }
}

private fun intervalLabel(context: Context, minutes: Int): String =
    context.getString(R.string.data_backup_auto_interval_value, minutesText(context, minutes))

/** 把分钟数写成「10 分钟 / 2 小时 / 1 天 / 1 小时 30 分钟」。 */
private fun minutesText(context: Context, minutes: Int): String {
    val value = AutoBackupInterval.normalize(minutes)
    return when {
        value % 1440 == 0 -> context.getString(R.string.data_backup_unit_day, value / 1440)
        value % 60 == 0 -> context.getString(R.string.data_backup_unit_hour, value / 60)
        value < 60 -> context.getString(R.string.data_backup_unit_minute, value)
        else -> context.getString(R.string.data_backup_unit_hour_minute, value / 60, value % 60)
    }
}


@Composable
private fun BackupPreference(
    title: String, summary: String? = null, enabled: Boolean = true,
    startAction: (@Composable () -> Unit)? = null, onClick: () -> Unit,
) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiPreference(title = title, summary = summary, enabled = enabled, icon = startAction, onClick = onClick)
    } else {
        ArrowPreference(title = title, summary = summary, enabled = enabled,
            startAction = { startAction?.invoke() }, onClick = onClick)
    }
}

@Composable
private fun BackupSwitchPreference(title: String, summary: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiSwitchPreference(title = title, summary = summary, checked = checked, onCheckedChange = onCheckedChange)
    } else {
        SwitchPreference(title = title, summary = summary, checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BackupDivider() {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) Divider()
}

@Composable
private fun BackupInputDialog(
    title: String, summary: String, label: String, value: String,
    onValueChange: (String) -> Unit, confirmEnabled: Boolean = true,
    onDismiss: () -> Unit, onConfirm: () -> Unit,
) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiConfirmDialog(visible = true, title = title, message = summary,
            confirmText = stringResource(R.string.action_confirm), confirmEnabled = confirmEnabled,
            onConfirm = onConfirm, onDismissRequest = onDismiss) {
            CouiTextField(value = value, onValueChange = onValueChange, label = label, singleLine = true)
        }
    } else {
        WindowDialog(show = true, title = title, summary = summary, onDismissRequest = onDismiss) {
            val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.7f
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    TextField(value = value, onValueChange = onValueChange, label = label,
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                MiuixDialogActions(confirmText = stringResource(R.string.action_confirm),
                    confirmEnabled = confirmEnabled, onCancel = onDismiss, onConfirm = onConfirm)
            }
        }
    }
}
