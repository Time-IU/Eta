// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import android.content.Context
import org.json.JSONObject
import java.net.URI

enum class LocalSpeechBackend { GGUF, SENSEVOICE_ONNX }
enum class LocalSpeechStage { DOWNLOADING, IMPORTING, VERIFYING, INSTALLING, COMPLETE }
data class LocalSpeechProgress(val stage: LocalSpeechStage, val bytes: Long, val total: Long)
data class LocalSpeechModelFile(val name: String, val sizeBytes: Long, val sha256: String,
    val pinnedUrl: String, val revision: String, val role: String)
data class LocalSpeechModelDescriptor(val id: String, val displayName: String,
    val engine: LocalSpeechBackend, val architecture: String, val quantization: String,
    val description: String, val licenseName: String, val licenseAsset: String,
    val available: Boolean, val availabilityReason: String?, val files: List<LocalSpeechModelFile>,
    val recommended: Boolean) { val sizeBytes: Long get() = files.sumOf { it.sizeBytes } }
data class LocalSpeechInstallation(val modelId: String, val installed: Boolean, val detail: String)

/** Metadata only; no recognizer class or JNI initialization is reachable here. */
internal object LocalSpeechRegistry {
    const val DEFAULT_ID = "sensevoice_small_int8"
    private val ids = setOf("qwen3_asr_06b_q4_k_m", "qwen3_asr_06b_q5_k_m",
        "sensevoice_small_f16", "sensevoice_small_q8_0", "sensevoice_small_q4_k_m", DEFAULT_ID)
    fun catalog(context: Context): List<LocalSpeechModelDescriptor> {
        val bytes = context.assets.open("local_speech/catalog.json").use { input ->
            val data = input.readBytesBounded(131_072); data.toString(Charsets.UTF_8)
        }
        return parse(bytes)
    }
    internal fun parse(bytes: String): List<LocalSpeechModelDescriptor> {
        require(bytes.length <= 131_072)
        val root = JSONObject(bytes)
        require(root.getInt("schemaVersion") == 1 && root.getString("recommendedModelId") == DEFAULT_ID)
        val models = root.getJSONArray("models")
        require(models.length() == 6)
        val result = (0 until models.length()).map { index ->
            val m = models.getJSONObject(index)
            val id = m.getString("id"); require(id in ids)
            val backend = LocalSpeechBackend.valueOf(m.getString("engine"))
            val files = m.getJSONArray("files").let { a -> (0 until a.length()).map { n ->
                val f = a.getJSONObject(n)
                LocalSpeechModelFile(f.getString("name"), f.getLong("sizeBytes"), f.getString("sha256"),
                    f.getString("pinnedUrl"), f.getString("revision"), f.getString("role")).also { file ->
                    require(file.name.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && file.name != "." && file.name != "..")
                    require(file.sizeBytes in 1..2_000_000_000L && file.sha256.matches(Regex("[a-f0-9]{64}")))
                    require(file.revision.matches(Regex("[a-f0-9]{40}")))
                    val url = URI(file.pinnedUrl)
                    require(url.scheme == "https" && url.host == "huggingface.co" && url.port == -1 &&
                        url.rawQuery == null && url.fragment == null && url.userInfo == null)
                    val publisher = if (backend == LocalSpeechBackend.GGUF) "handy-computer" else "csukuangfj"
                    require(url.path.startsWith("/$publisher/") && url.path.endsWith("/resolve/${file.revision}/${file.name}"))
                }
            } }
            require(files.map { it.name }.distinct().size == files.size)
            require(files.all { it.role == if (it.name == "tokens.txt") "tokens" else "model" })
            require(m.getLong("sizeBytes") == files.sumOf { it.sizeBytes })
            require(if (backend == LocalSpeechBackend.GGUF) files.size == 1 && files.single().name.endsWith(".gguf")
                else files.map { it.name }.toSet() == setOf("model.int8.onnx", "tokens.txt"))
            val architecture = m.getString("architecture")
            require(architecture in setOf("qwen3_asr", "sensevoice"))
            require(backend != LocalSpeechBackend.SENSEVOICE_ONNX || architecture == "sensevoice")
            val license = m.getString("licenseAsset")
            require(license in setOf("local_speech/licenses/Qwen-Apache-2.0.txt", "local_speech/licenses/SenseVoice-Model-License-1.1.txt"))
            LocalSpeechModelDescriptor(id, m.getString("displayName"), backend, architecture,
                m.getString("quantization"), m.getString("description"), m.getString("licenseName"), license,
                m.getBoolean("available"), m.optString("availabilityReason").takeUnless { it.isBlank() || it == "null" },
                files, id == DEFAULT_ID)
        }
        require(result.map { it.id }.toSet() == ids)
        return result
    }
    fun model(context: Context, id: String): LocalSpeechModelDescriptor =
        catalog(context).firstOrNull { it.id == id } ?: error("未知本机模型，请重新选择")
}

internal fun java.io.InputStream.readBytesBounded(limit: Int): ByteArray {
    val output = java.io.ByteArrayOutputStream(minOf(limit, 8192)); val buffer = ByteArray(8192)
    while (true) {
        val n = read(buffer); if (n == -1) break
        require(n > 0 && output.size().toLong() + n <= limit) { "元数据过大或输入无效" }
        output.write(buffer, 0, n)
    }
    return output.toByteArray()
}
