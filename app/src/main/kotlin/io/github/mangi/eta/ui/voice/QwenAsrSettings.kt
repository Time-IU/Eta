package io.github.mangi.eta.ui.voice

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.voice.qwen.QwenModelStore
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.AsrProvider
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton

/** Only explicit actions download/install/select; entering this card never downloads a model. */
@Composable
internal fun QwenAsrConnectionSection(store: SpeechSettingsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember(context) { mutableStateOf<QwenModelStore.Status?>(null) }
    var busy by remember(context) { mutableStateOf(false) }
    var operation by remember(context) { mutableStateOf("正在检测模型") }
    var progress by remember(context) { mutableStateOf<QwenModelStore.Progress?>(null) }
    var message by remember(context) { mutableStateOf<String?>(null) }
    var job by remember(context) { mutableStateOf<Job?>(null) }
    var generation by remember(context) { mutableStateOf(0) }

    fun runOperation(label: String, action: suspend ((QwenModelStore.Progress) -> Unit) -> Unit) {
        if (busy || store.saving) return
        busy = true
        status = null
        operation = label
        progress = null
        message = null
        val token = ++generation
        job = scope.launch {
            try {
                action { update ->
                    scope.launch(Dispatchers.Main.immediate) {
                        if (token == generation && busy) progress = update
                    }
                }
                if (token == generation) {
                    operation = "正在重新校验六个模型文件"
                    progress = null
                    status = QwenModelStore.inspect(context)
                }
            } catch (error: CancellationException) {
                if (token == generation) message = "操作已取消；原有模型不会被删除，请重新检测。"
                throw error
            } catch (error: Exception) {
                if (token == generation) message = error.message ?: "模型操作失败，请重新检测后重试"
            } finally {
                if (token == generation) { busy = false; progress = null; job = null }
            }
        }
    }

    LaunchedEffect(context) { runOperation("正在检测模型") { } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runOperation("正在导入模型") { report ->
            QwenModelStore.importModel(context, open = {
                context.contentResolver.openInputStream(uri) ?: error("无法读取所选模型包，请重新选择")
            }, onProgress = report)
            message = "导入已校验；不会自动切换识别服务，请点击“使用此模型并保存”。"
        }
    }
    val available = !busy && !store.saving && store.loaded
    SpeechGroupCard(title = "Qwen3-ASR-0.6B · 本地 ONNX (int8)") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            // Plain Text has no fixed height/maxLines: long details wrap in both scrolling settings pages.
            Text("模型未内嵌：六文件共 987,015,347 B（约 941.29 MiB）；下载包 878,702,423 B。仅用户点击下载才联网，识别始终离线。")
            Text("支持 ZIP / TAR.BZ2：conv_frontend.onnx、encoder.int8.onnx、decoder.int8.onnx，以及 tokenizer/merges.txt、tokenizer/tokenizer_config.json、tokenizer/vocab.json。")
            Text("原始 HF Safetensors 不兼容，须先转换为受支持的 sherpa Qwen ONNX + tokenizer 六文件包；文件和校验值必须匹配。选择器通用 MIME 不代表支持任意模型。")
            Text("单次最长 30 秒；说完识别，非实时逐字。停止后分段解码（每段不超过 10 秒）。首次校验/加载较慢；测试原生进程内存约 1.5 GiB，设备实际占用可能不同。")
            Text(if (busy) operation else when (status?.state) {
                QwenModelStore.State.INSTALLED -> "状态：已安装并通过六文件校验"
                QwenModelStore.State.INVALID -> "状态：模型无效，请重新下载或导入"
                QwenModelStore.State.MISSING -> "状态：尚未安装"
                null -> "状态：尚未确认，请重新检测"
            })
            status?.let { Text("${it.detail}\n已检测文件大小：${it.sizeBytes} B") }
            progress?.let {
                val count = if (it.totalBytes > 0) {
                    "${it.bytesCompleted} / ${it.totalBytes} B（${String.format(Locale.ROOT, "%.1f", it.bytesCompleted.toDouble() / it.totalBytes * 100)}%）"
                } else "${it.bytesCompleted} B（总量未知）"
                Text("${it.stage}：$count")
            }
            message?.let { Text(it) }
        }
        QwenModelAction("下载官方模型", available) {
            runOperation("正在下载官方模型") { QwenModelStore.download(context, onProgress = it) }
        }
        QwenModelAction("导入 ZIP / TAR.BZ2", available) {
            picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/x-bzip2", "application/x-bzip", "application/x-bzip-compressed-tar", "application/x-tbz2", "application/x-gtar", "application/bzip2", "application/x-tar", "application/octet-stream"))
        }
        QwenModelAction("重新检测", available) { runOperation("正在重新检测模型") { } }
        if (busy) QwenModelAction("取消操作", true) { job?.cancel() }
        QwenModelAction("使用此模型并保存", available && status?.installed == true) {
            runOperation("正在确认模型后保存") {
                val verified = QwenModelStore.inspect(context)
                check(verified.installed) { "模型未通过校验，请先下载或重新导入" }
                store.edit(store.settings.copy(asr = AsrProvider.QWEN3_LOCAL))
                store.save(validate = {}, persistCredentials = false)
            }
        }
    }
}

@Composable
private fun QwenModelAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiButton(text = text, enabled = enabled, onClick = onClick, modifier = modifier)
    } else {
        TextButton(text = text, enabled = enabled, onClick = onClick, modifier = modifier)
    }
}
