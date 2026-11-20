package io.github.mangi.eta.ui.screens.tasks

import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.ui.components.EtaPreferenceDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.display.MainScreenFallbackApproval
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaSwitchPreference
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Both route names render the same single-engine controls and saved settings. */
@Composable
internal fun VirtualScreenSettingsScreen(onBack: () -> Unit) = UnifiedCanvasSettingsScreen(onBack)

@Composable
internal fun UnifiedCanvasSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Flow identity must not change when save/ACK/permission state recomposes this screen.
    val settingsReader = remember { CanvasSettingsReader {
        SettingsDataStore.strictCanvasReadFlow().distinctCanvasSettings { value ->
            CanvasSettingsSnapshot(
                value.virtualScreenEnabled, value.unifiedCanvasBackgroundEnabled,
                value.virtualScreenOffEnabled, value.virtualScreenFallbackEnabled,
                value.virtualScreenAutoRestartApps, value.virtualScreenIdleTimeoutMinutes,
                value.virtualScreenFloatWindowEnabled, value.virtualScreenKeepScreenOn,
                value.virtualScreenFloatEdgeCollapseEnabled,
            )
        }
    } }
    val readState by settingsReader.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val gateSync by SettingsDataStore.canvasGateSync.collectAsStateWithLifecycle()
    var actionFailed by remember { mutableStateOf(false) }
    var pendingSaves by remember { mutableIntStateOf(0) }
    var retrying by remember { mutableStateOf(false) }
    val saveMutex = remember { Mutex() }
    fun launchSettings(onComplete: () -> Unit = {}, block: suspend () -> Unit) {
        // Serialize durable writes without dimming/re-enabling every row on each click.
        // A second control's click is queued, not silently discarded while an ACK is pending.
        if (!settingsReader.state.value.canEdit) { onComplete(); return }
        pendingSaves++
        scope.launch {
            try {
                saveMutex.withLock {
                    if (!settingsReader.state.value.canEdit) return@withLock
                    actionFailed = false
                    io.github.mangi.eta.config.CanvasSettingsErrorBoundary.action(onFailure = {
                        actionFailed = true
                    }) { withContext(Dispatchers.IO) { block() } }
                }
            } finally {
                pendingSaves--
                onComplete()
            }
        }
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(settingsReader, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { settingsReader.collect() }
    }
    var overlayGranted by remember { mutableStateOf(AndroidSettings.canDrawOverlays(context)) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                overlayGranted = AndroidSettings.canDrawOverlays(context)
                io.github.mangi.eta.agent.display.VirtualScreenFloatWindow.onOverlayPermissionChanged()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 最近一次导出结果：路径直接展示出来，并允许一键打开。
    var lastExport by remember { mutableStateOf<String?>(null) }

    // A cold first read must never render writable default Settings(). Keep the list owner
    // outside the loading branch so loading/resume/save cannot replace its scroll identity.
    val settings = readState.snapshot
    val canEdit = readState.canEdit
    MiuixScaffoldPage(title = stringResource(R.string.unified_canvas_title), onBack = onBack,
        listState = listState) {
        item(key = "canvas_controls") {
            EtaPreferenceGroup {
                CanvasSettingsStatusSlot(
                    retrying = retrying, actionFailed = actionFailed, gateSync = gateSync,
                    readFailed = readState.failed, reading = readState.reading,
                    onRetry = {
                        if (readState.failed) settingsReader.retry()
                        else if (!retrying) {
                            retrying = true
                            launchSettings(onComplete = { retrying = false }) {
                                SettingsDataStore.reconcileCanvasGate()
                            }
                        }
                    },
                )
                if (settings == null) return@EtaPreferenceGroup
                EtaPreferenceDivider(hasLeading = false)
                EtaSwitchPreference(
                    title = stringResource(R.string.unified_canvas_enable),
                    summary = stringResource(R.string.unified_canvas_enable_summary),
                    checked = settings.virtualScreenEnabled,
                    enabled = canEdit,
                    onCheckedChange = { value ->
                        launchSettings {
                            SettingsDataStore.updateSettings { it.copy(virtualScreenEnabled = value) }
                            if (!value) {
                                VirtualScreenSession.revokePermission()
                                MainScreenFallbackApproval.cancelAll()
                            }
                        }
                    },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaSwitchPreference(
                    title = stringResource(R.string.unified_canvas_background),
                    summary = stringResource(R.string.unified_canvas_background_summary),
                    checked = settings.unifiedCanvasBackgroundEnabled,
                    enabled = canEdit && settings.virtualScreenEnabled,
                    onCheckedChange = { value -> launchSettings {
                        SettingsDataStore.updateSettings { it.copy(unifiedCanvasBackgroundEnabled = value) }
                    } },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaSwitchPreference(
                    title = stringResource(R.string.virtual_screen_off_enable),
                    summary = stringResource(R.string.virtual_screen_off_summary),
                    checked = settings.virtualScreenOffEnabled,
                    enabled = canEdit && settings.virtualScreenEnabled,
                    onCheckedChange = { value -> launchSettings {
                        SettingsDataStore.updateSettings { it.copy(virtualScreenOffEnabled = value) }
                    } },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaSwitchPreference(
                    title = stringResource(R.string.virtual_screen_fallback_enable),
                    summary = stringResource(R.string.virtual_screen_fallback_summary),
                    checked = settings.virtualScreenFallbackEnabled,
                    enabled = canEdit && settings.virtualScreenEnabled,
                    onCheckedChange = { value -> launchSettings {
                        SettingsDataStore.updateSettings { it.copy(virtualScreenFallbackEnabled = value) }
                    } },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaSwitchPreference(
                    title = stringResource(R.string.virtual_screen_restart_enable),
                    summary = stringResource(R.string.virtual_screen_restart_summary),
                    checked = settings.virtualScreenAutoRestartApps,
                    enabled = canEdit && settings.virtualScreenEnabled,
                    onCheckedChange = { value -> launchSettings {
                        SettingsDataStore.updateSettings { it.copy(virtualScreenAutoRestartApps = value) }
                    } },
                )
                EtaPreferenceDivider(hasLeading = false)
                // Recreate only the picker when the read gate changes, dismissing a stale menu.
                key(canEdit) {
                    CanvasIdleTimeoutPreference(
                        currentMinutes = settings.virtualScreenIdleTimeoutMinutes,
                        enabled = canEdit,
                        onMinutesSelected = { minutes -> launchSettings {
                            SettingsDataStore.updateSettings { it.copy(virtualScreenIdleTimeoutMinutes = minutes) }
                        } },
                    )
                }
                EtaPreferenceDivider(hasLeading = false)
                EtaSwitchPreference(
                    title = stringResource(R.string.lingscreen_float_enable),
                    summary = stringResource(R.string.lingscreen_float_summary) + if (overlayGranted) "" else " · " + stringResource(R.string.overlay_permission_choose_app),
                    checked = settings.virtualScreenFloatWindowEnabled,
                    enabled = canEdit && settings.virtualScreenEnabled,
                    onCheckedChange = { value ->
                        if (value && !android.provider.Settings.canDrawOverlays(context)) {
                            io.github.mangi.eta.ui.navigation.OverlayPermissionNavigation.open(context)
                        }
                        launchSettings {
                            SettingsDataStore.updateSettings { it.copy(virtualScreenFloatWindowEnabled = value) }
                        }
                    },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaSwitchPreference(
                    title = stringResource(R.string.lingscreen_keep_screen_on),
                    summary = stringResource(R.string.lingscreen_keep_screen_on_summary),
                    checked = settings.virtualScreenKeepScreenOn,
                    enabled = canEdit,
                    onCheckedChange = { value ->
                        io.github.mangi.eta.agent.display.VscrDiag.line("不息屏 开关=" + value)
                        launchSettings {
                            SettingsDataStore.updateSettings { it.copy(virtualScreenKeepScreenOn = value) }
                            // 无条件同步：关掉时必须能真的释放，不能因为会话状态判断漏掉。
                            io.github.mangi.eta.agent.display.VirtualScreenWakeLock.sync(
                                context,
                                value && VirtualScreenSession.isActive(),
                            )
                        }
                    },
                )
                EtaPreferenceDivider(hasLeading = false)
                EtaSwitchPreference(
                    title = stringResource(R.string.lingscreen_edge_collapse),
                    summary = stringResource(R.string.lingscreen_edge_collapse_summary),
                    checked = settings.virtualScreenFloatEdgeCollapseEnabled,
                    enabled = canEdit && settings.virtualScreenFloatWindowEnabled,
                    onCheckedChange = { value ->
                        launchSettings {
                            SettingsDataStore.updateSettings { it.copy(virtualScreenFloatEdgeCollapseEnabled = value) }
                        }
                    },
                )
            }
        }
        item(key = "canvas_log_dir") {
            Text(
                text = stringResource(R.string.cleanup_canvas_log_dir),
                modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding, vertical = 4.dp),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        lastExport?.let { path ->
            item(key = "canvas_last_export") {
                Text(
                    text = stringResource(R.string.cleanup_canvas_last_export, path),
                    modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding),
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            item(key = "canvas_open_export") {
                EtaTextButton(
                    text = stringResource(R.string.cleanup_canvas_open_log),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = EtaPreferenceDefaults.SidePadding, vertical = 4.dp),
                    onClick = {
                        runCatching {
                            val file = java.io.File(path)
                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                context,
                                context.packageName + ".fileprovider",
                                file,
                            )
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW)
                                    .setDataAndType(uri, "text/plain")
                                    .addFlags(
                                        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                            android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                    )
                            )
                        }
                    },
                )
            }
        }
        item(key = "canvas_export") {
            EtaTextButton(
                text = stringResource(R.string.lingscreen_export_log),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = EtaPreferenceDefaults.SidePadding, vertical = 8.dp),
                onClick = {
                    scope.launch(Dispatchers.IO) {
                        val message = runCatching {
                            val src = java.io.File(context.getExternalFilesDir(null), "vscr.log")
                            if (!src.exists()) error(context.getString(R.string.cleanup_canvas_no_log))
                            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                                .format(java.util.Date())
                            // 导出到共享产物目录：文件管理器、Eta 工作区、其他 App 都能直接看到。
                            val dir = java.io.File("/sdcard/ai共享文件夹/灵屏日志")
                            if (!dir.exists()) dir.mkdirs()
                            val dst = java.io.File(dir, "lingscreen-log-$stamp.log")
                            val latest = java.io.File(dir, "lingscreen-latest.log")
                            src.copyTo(dst, overwrite = true)
                            src.copyTo(latest, overwrite = true)
                            lastExport = dst.absolutePath
                            context.getString(R.string.cleanup_canvas_log_exported, dst.name, src.length() / 1024)
                        }.getOrElse { context.getString(R.string.cleanup_canvas_export_failed, it.message ?: it.javaClass.simpleName) }
                        io.github.mangi.eta.agent.display.VscrDiag.line("导出灵屏日志 " + message)
                        withContext(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
                        }
                    }
                },
            )
        }
        item(key = "canvas_usage_title") {
            Text(
                text = stringResource(R.string.lingscreen_usage_title),
                modifier = Modifier.padding(
                    start = EtaPreferenceDefaults.SidePadding,
                    end = EtaPreferenceDefaults.SidePadding,
                    top = 14.dp,
                    bottom = 2.dp,
                ),
                style = MiuixTheme.textStyles.body1,
            )
        }
        item(key = "canvas_usage_body") {
            Text(
                text = stringResource(R.string.lingscreen_usage_body),
                modifier = Modifier.padding(
                    start = EtaPreferenceDefaults.SidePadding,
                    end = EtaPreferenceDefaults.SidePadding,
                    bottom = 18.dp,
                ),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

/** One themed preference row; native popup dismissal has no persistence callback. */
@Composable
private fun CanvasIdleTimeoutPreference(
    currentMinutes: Int,
    enabled: Boolean,
    onMinutesSelected: (Int) -> Unit,
) {
    val selection = remember(currentMinutes) { CanvasIdleTimeoutSelection(currentMinutes) }
    val labels = selection.minutes.map { minutes ->
        if (minutes == 0) stringResource(R.string.virtual_screen_idle_never)
        else stringResource(R.string.virtual_screen_idle_minutes, minutes)
    }
    val onSelected: (Int) -> Unit = { index ->
        selection.changedMinutes(index, enabled)?.let(onMinutesSelected)
    }
    if (io.github.mangi.eta.ui.app.LocalUiStyle.current ==
        io.github.mangi.eta.data.model.AppearanceUiStyle.COUI) {
        io.github.mangi.eta.ui.components.coui.CouiDropdownPreference(
            title = stringResource(R.string.virtual_screen_idle_timeout),
            items = labels,
            selectedIndex = selection.selectedIndex,
            enabled = enabled,
            onSelectedIndexChange = onSelected,
        )
    } else {
        top.yukonga.miuix.kmp.preference.OverlayDropdownPreference(
            title = stringResource(R.string.virtual_screen_idle_timeout),
            items = labels,
            selectedIndex = selection.selectedIndex,
            enabled = enabled,
            onSelectedIndexChange = onSelected,
        )
    }
}
