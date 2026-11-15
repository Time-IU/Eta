package io.github.mangi.eta.ui.voice

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.voice.local.LocalSpeechInstallation
import io.github.mangi.eta.agent.voice.local.LocalSpeechModelDescriptor
import io.github.mangi.eta.agent.voice.local.LocalSpeechModelStore
import io.github.mangi.eta.agent.voice.local.LocalSpeechProgress
import io.github.mangi.eta.agent.voice.local.LocalSpeechStage
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiDropdownPreference
import io.github.mangi.eta.ui.components.coui.CouiPreference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference

/** Catalog/measurements and complete installation inspection stay off the UI thread. */
@Composable
internal fun LocalSpeechConnectionSection(store: SpeechSettingsStore) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var models by remember(context) { mutableStateOf<List<LocalSpeechModelDescriptor>>(emptyList()) }
    var selected by remember(context) { mutableStateOf<String?>(null) }
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    // SAF may recreate the activity. Never infer the import destination from the current dropdown.
    var importTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var installations by remember(context) { mutableStateOf<Map<String, LocalSpeechInstallation>>(emptyMap()) }
    var progress by remember(context) { mutableStateOf<LocalSpeechProgress?>(null) }
    var message by remember(context) { mutableStateOf<String?>(null) }
    var operationDetail by remember(target) { mutableStateOf<String?>(null) }
    var busy by remember(context) { mutableStateOf(false) }
    var cancelling by remember(context) { mutableStateOf(false) }
    var job by remember(context) { mutableStateOf<Job?>(null) }
    var generation by remember(context) { mutableStateOf(0) }
    var measurements by remember(context) { mutableStateOf<LocalSpeechMeasurements?>(null) }
    var metadataFailure by remember(context) { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(context, reload) {
        metadataFailure = false
        try {
            val catalog = withContext(Dispatchers.IO) { LocalSpeechModelStore.availableModels(context) }
            val saved = withContext(Dispatchers.IO) { LocalSpeechModelStore.selectedId(context) }
            models = catalog
            selected = saved
            target = target?.takeIf { id -> catalog.any { it.id == id } }
                ?: saved.takeIf { id -> catalog.any { it.id == id } }
                ?: catalog.first { it.recommended }.id
            measurements = withContext(Dispatchers.IO) { readLocalSpeechMeasurements(context) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { metadataFailure = true }
    }
    fun operate(id: String, action: suspend ((LocalSpeechProgress) -> Unit) -> Unit) {
        if (busy || store.saving || !store.loaded || target != id) return
        val token = ++generation
        busy = true
        cancelling = false
        progress = null
        message = null
        operationDetail = null
        job = scope.launch {
            try {
                coroutineScope {
                    // One bounded consumer; at most one visible update per 100 ms, plus completion.
                    val updates = Channel<LocalSpeechProgress>(Channel.CONFLATED)
                    val reporter = launch(Dispatchers.Main.immediate) {
                        for (first in updates) {
                            var latest = first
                            if (latest.stage != LocalSpeechStage.COMPLETE) delay(100)
                            while (true) latest = updates.tryReceive().getOrNull() ?: break
                            if (generation == token && busy && target == id) progress = latest
                        }
                    }
                    try { action { updates.trySend(it) } }
                    finally { updates.close(); reporter.join() }
                }
                val inspected = withContext(Dispatchers.IO) { LocalSpeechModelStore.inspect(context, id) }
                if (generation == token && target == id) installations = installations + (id to inspected)
            } catch (cancelled: CancellationException) {
                if (generation == token) message = "操作已取消，可重新检测安装状态。"
                throw cancelled
            } catch (error: Exception) {
                if (generation == token) {
                    operationDetail = error.message?.take(240)
                    installations = installations - id
                    message = "操作未完成，请检查文件、网络或存储后重新检测。"
                }
            } finally {
                if (generation == token) {
                    busy = false
                    cancelling = false
                    progress = null
                    job = null
                }
            }
        }
    }
    LaunchedEffect(target, store.loaded) {
        val id = target ?: return@LaunchedEffect
        if (store.loaded) operate(id) { }
    }
    // Inspect a different saved selection independently, without claiming that selection means installed.
    LaunchedEffect(selected, target) {
        val id = selected ?: return@LaunchedEffect
        if (id != target && installations[id] == null && models.any { it.id == id }) {
            val inspected = withContext(Dispatchers.IO) { LocalSpeechModelStore.inspect(context, id) }
            installations = installations + (id to inspected)
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = importTarget
        importTarget = null
        if (uri != null && id != null && id == target) operate(id) { report ->
            LocalSpeechModelStore.importModel(context, id, opener = {
                context.contentResolver.openInputStream(uri) ?: error("无法读取所选文件")
            }, onProgress = report)
            message = "导入校验完成，尚未切换识别模型。"
        }
    }
    val model = models.firstOrNull { it.id == target }
    if (model == null) {
        SpeechGroupCard(title = "本机离线") {
            SpeechNote(if (metadataFailure) "模型目录读取失败，请重试。" else "正在读取模型目录…")
            if (metadataFailure) LocalModelAction("重试", true) { reload++ }
        }
        return
    }
    val enabled = !busy && !store.saving && store.loaded && importTarget == null
    val status = installations[model.id]
    SpeechGroupCard(title = "正在使用") {
        val current = models.firstOrNull { it.id == selected }
        val summary = "${current?.shortLabel() ?: "未知已保存模型"} · 已选 · ${localInstallationLabel(installations[selected])}"
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            CouiPreference(title = "下次录音", summary = summary, showChevron = false)
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
                LocalSpeechText(summary)
            }
        }
    }
    SpeechGroupCard(title = "模型概览") {
        val largeText = LocalDensity.current.fontScale > 1.25f
        val labels = models.map { if (largeText) it.shortLabel().replace("SenseVoice", "SV") else it.shortLabel() }
        val choose: (Int) -> Unit = { index ->
            if (enabled) models.getOrNull(index)?.let {
                target = it.id
                message = null
                progress = null
            }
        }
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            CouiDropdownPreference(title = "模型", items = labels, enabled = enabled,
                selectedIndex = models.indexOfFirst { it.id == target }, onSelectedIndexChange = choose)
        } else {
            OverlayDropdownPreference(title = "模型", items = labels, enabled = enabled,
                selectedIndex = models.indexOfFirst { it.id == target }, onSelectedIndexChange = choose)
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LocalSpeechText(model.overview(), secondary = true)
            LocalSpeechMetrics(model, measurements?.models?.get(model.id))
            LocalSpeechStatus(model, status, busy, message)
            if (busy) LocalSpeechProgressBar(progress)
        }
        LocalModelAction(if (selected == target) "确认使用并保存" else "使用此模型并保存",
            enabled && model.available && status?.installed == true && status.modelId == target) {
            val id = model.id
            operate(id) {
                check(withContext(Dispatchers.IO) { LocalSpeechModelStore.inspect(context, id).installed }) { "模型未通过校验" }
                val config = store.settings.copy(asr = AsrProvider.LOCAL)
                SpeechSettingsRepository.save(config)
                check(SpeechSettingsRepository.settings().asr == AsrProvider.LOCAL) { "本机离线设置保存未确认" }
                withContext(Dispatchers.IO) {
                    LocalSpeechModelStore.selectModel(context, id)
                    check(LocalSpeechModelStore.selectedId(context) == id) { "模型选择保存未确认" }
                }
                selected = id
                store.edit(config)
                message = "已保存，下次录音生效。"
            }
        }
        LocalModelAction("下载模型（联网）", enabled && model.available) {
            operate(model.id) { report -> LocalSpeechModelStore.download(context, model.id, onProgress = report) }
        }
        LocalModelAction("导入 GGUF / ZIP", enabled && model.available) {
            importTarget = model.id
            picker.launch(arrayOf("application/octet-stream", "application/zip", "application/x-zip-compressed"))
        }
        if (busy) LocalModelAction(if (cancelling) "正在取消…" else "取消操作", !cancelling) {
            cancelling = true
            job?.cancel() // Keep the handle and busy state until the operation's finally block.
        }
    }
    LocalSpeechModelInfo(model, measurements, status, operationDetail, enabled, onInspect = { operate(model.id) { } })
}

internal fun LocalSpeechModelDescriptor.shortLabel(): String = when (id) {
    "qwen3_asr_06b_q4_k_m" -> "Qwen Q4_K_M"
    "qwen3_asr_06b_q5_k_m" -> "Qwen Q5_K_M"
    "sensevoice_small_f16" -> "SenseVoice F16"
    "sensevoice_small_q8_0" -> "SenseVoice Q8_0"
    "sensevoice_small_int8" -> "SenseVoice INT8"
    "sensevoice_small_q4_k_m" -> "SV Q4_K_M"
    else -> displayName
}

@Composable
internal fun LocalModelAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiButton(text = text, enabled = enabled, onClick = onClick, modifier = modifier)
    } else TextButton(text = text, enabled = enabled, onClick = onClick, modifier = modifier)
}
