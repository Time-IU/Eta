package io.github.mangi.eta.ui.screens.diagnostics

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Security
import androidx.compose.ui.graphics.Color
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.CleanupSettingsEntry
import io.github.mangi.eta.ui.CleanupSettingsRow
import io.github.mangi.eta.ui.navigation.AppRoute
import androidx.core.content.FileProvider
import io.github.mangi.eta.core.CrashRecorder
import io.github.mangi.eta.core.DiagnosticsBundle
import io.github.mangi.eta.ui.components.EtaPreference
import io.github.mangi.eta.ui.components.EtaPreferenceDivider
import io.github.mangi.eta.ui.components.EtaPreferenceGroup
import io.github.mangi.eta.ui.components.EtaPreferenceGroupTitle
import io.github.mangi.eta.ui.components.EtaTextButton
import io.github.mangi.eta.ui.components.EtaWindowDialog
import io.github.mangi.eta.ui.components.MiuixDialogActions
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Single diagnostics entrance: existing self-check, patrol, and read-only log/bundle export. */
@Composable
internal fun DiagnosticsCenterScreen(onBack: () -> Unit, onNavigate: (AppRoute) -> Unit) {
    // 启动系统分享面板必须用 Activity 上下文（或至少带 NEW_TASK）：
    // applicationContext 直接 startActivity 会抛 AndroidRuntimeException，失败被吞就是「闪一下」。
    val host = LocalContext.current
    val context = host.applicationContext
    val scope = rememberCoroutineScope()
    var crashes by remember { mutableStateOf<List<File>>(emptyList()) }
    var bundleCount by remember { mutableStateOf(0) }
    var autoCount by remember { mutableStateOf(0) }
    var pending by remember { mutableStateOf(false) }
    var payload by remember { mutableStateOf<ByteArray?>(null) }
    var sharingName by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var lastSaved by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableStateOf(0) }

    // 目录不可写（没给所有文件访问权限等）时退回系统文件选择器。
    val saver = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val bytes = payload
        payload = null
        if (uri == null || bytes == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { stream ->
                        stream.write(bytes)
                    } ?: error(context.getString(R.string.cleanup_diag_output_unavailable))
                }
            }.isSuccess
            Toast.makeText(
                context,
                context.getString(if (saved) R.string.cleanup_diag_saved else R.string.cleanup_diag_save_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    LaunchedEffect(round) {
        withContext(Dispatchers.IO) {
            crashes = CrashRecorder.records(context)
            bundleCount = countZips(File(DiagnosticsBundle.DEFAULT_DIR))
            autoCount = countZips(File(DiagnosticsBundle.DEFAULT_DIR, "自动"))
        }
    }

    val exportBundle: () -> Unit = {
        if (!pending) {
            pending = true
            scope.launch {
                val bytes = runCatching {
                    withContext(Dispatchers.IO) { DiagnosticsBundle.collect(context) }
                }.getOrNull()
                pending = false
                if (bytes == null) {
                    Toast.makeText(context, R.string.cleanup_diag_generate_failed, Toast.LENGTH_SHORT).show()
                } else {
                    val name = DiagnosticsBundle.defaultFileName()
                    val saved = withContext(Dispatchers.IO) {
                        runCatching {
                            val dir = File(DiagnosticsBundle.DEFAULT_DIR).apply { mkdirs() }
                            File(dir, name).apply { writeBytes(bytes) }
                        }.getOrNull()
                    }
                    if (saved != null) {
                        lastSaved = "${saved.name} · ${saved.length() / 1024} KB"
                        round += 1
                        Toast.makeText(context, context.getString(R.string.cleanup_diag_saved_path, saved.absolutePath), Toast.LENGTH_LONG).show()
                    } else {
                        payload = bytes
                        saver.launch(name)
                    }
                }
            }
        }
    }

    val share: (File) -> Unit = { file ->
        if (sharingName == null) {
            sharingName = file.name
            scope.launch {
                val uri = runCatching {
                    withContext(Dispatchers.IO) {
                        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
                        val target = File(dir, file.name)
                        target.writeBytes(file.readBytes())
                        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", target)
                    }
                }.getOrNull()
                sharingName = null
                if (uri == null) {
                    Toast.makeText(context, R.string.cleanup_diag_crash_export_failed, Toast.LENGTH_SHORT).show()
                } else {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    val chooser = Intent.createChooser(send, context.getString(R.string.cleanup_diag_share_title))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { host.startActivity(chooser) }.onFailure { error ->
                        Toast.makeText(
                            context,
                            context.getString(R.string.cleanup_diag_share_failed, error.message ?: error.javaClass.simpleName),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    MiuixScaffoldPage(title = stringResource(R.string.cleanup_diagnostics_center), onBack = onBack) {
        item(key = "diagnostics-head") {
            EtaPreferenceGroup {
                EtaPreference(
                    title = stringResource(R.string.cleanup_diag_intro),
                    summary = stringResource(R.string.cleanup_diag_intro_summary),
                )
            }
        }

        item(key = "diagnostics-checks") {
            EtaPreferenceGroup {
                CleanupSettingsRow(CleanupSettingsEntry(
                    R.string.cleanup_check_now, AppRoute.SelfCheck, Icons.Rounded.Security,
                    Color(0xFF4285F4), R.string.cleanup_check_now_summary,
                ), onNavigate)
                EtaPreferenceDivider(hasLeading = true)
                CleanupSettingsRow(CleanupSettingsEntry(
                    R.string.diag_patrol_title, AppRoute.DiagnosticsPatrol, Icons.Rounded.BugReport,
                    Color(0xFFFA7B17), R.string.cleanup_patrol_summary,
                ), onNavigate)
            }
        }

        item(key = "diagnostics-bundle") {
            EtaPreferenceGroupTitle(stringResource(R.string.cleanup_diag_export_group))
            EtaPreferenceGroup {
                EtaPreference(
                    title = stringResource(if (pending) R.string.cleanup_diag_collecting else R.string.cleanup_diag_generate),
                    summary = lastSaved?.let { stringResource(R.string.cleanup_diag_last, it) }
                        ?: stringResource(R.string.cleanup_diag_contents),
                    enabled = !pending,
                    endActions = {
                        EtaTextButton(
                            text = stringResource(if (pending) R.string.cleanup_diag_collecting else R.string.cleanup_diag_generate_action),
                            enabled = !pending,
                            onClick = exportBundle,
                        )
                    },
                )
                EtaPreferenceDivider(hasLeading = true)
                EtaPreference(
                    title = stringResource(R.string.cleanup_diag_saved_bundles),
                    summary = stringResource(R.string.cleanup_diag_counts, bundleCount, autoCount),
                )
            }
        }

        item(key = "diagnostics-crash") {
            EtaPreferenceGroupTitle(stringResource(R.string.cleanup_diag_crashes, crashes.size, CrashRecorder.MAX_KEPT))
            EtaPreferenceGroup {
                if (crashes.isEmpty()) {
                    EtaPreference(
                        title = stringResource(R.string.cleanup_diag_no_crashes),
                        summary = stringResource(R.string.cleanup_diag_no_crashes_summary),
                    )
                } else {
                    crashes.forEachIndexed { index, file ->
                        if (index > 0) EtaPreferenceDivider(hasLeading = true)
                        EtaPreference(
                            title = file.name,
                            summary = crashSummary(file),
                            enabled = sharingName == null,
                            endActions = {
                                EtaTextButton(
                                    text = stringResource(R.string.cleanup_diag_share),
                                    enabled = sharingName == null,
                                    onClick = { share(file) },
                                )
                            },
                        )
                    }
                    EtaPreferenceDivider(hasLeading = true)
                    EtaPreference(
                        title = stringResource(R.string.cleanup_diag_clear_title),
                        summary = stringResource(R.string.cleanup_diag_clear_summary),
                        endActions = {
                            EtaTextButton(
                                text = stringResource(R.string.cleanup_diag_clear),
                                onClick = { confirmClear = true },
                            )
                        },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.cleanup_diag_clear_confirm),
            summary = stringResource(R.string.cleanup_diag_clear_count, crashes.size),
            onDismissRequest = { confirmClear = false },
        ) {
            MiuixDialogActions(
                confirmText = stringResource(R.string.cleanup_diag_clear),
                destructive = true,
                onCancel = { confirmClear = false },
                onConfirm = {
                    confirmClear = false
                    scope.launch {
                        withContext(Dispatchers.IO) { CrashRecorder.clear(context) }
                        round += 1
                        Toast.makeText(context, R.string.cleanup_diag_cleared, Toast.LENGTH_SHORT).show()
                    }
                },
            )
        }
    }
}

private fun countZips(dir: File): Int = dir
    .listFiles { file -> file.isFile && file.name.startsWith("Eta-诊断包-") && file.name.endsWith(".zip") }
    ?.size ?: 0

private fun crashSummary(file: File): String {
    val kb = (file.length() + 1023) / 1024
    val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(file.lastModified()))
    return "$time · $kb KB"
}
