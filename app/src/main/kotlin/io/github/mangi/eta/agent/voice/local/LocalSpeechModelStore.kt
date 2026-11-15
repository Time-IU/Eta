// SPDX-License-Identifier: Apache-2.0
package io.github.mangi.eta.agent.voice.local

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.system.Os
import io.github.mangi.eta.agent.voice.SpeechErrorCode
import io.github.mangi.eta.agent.voice.SpeechFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.util.UUID

/** New storage only. Never deletes/replaces an existing installed directory, even if it is corrupt. */
object LocalSpeechModelStore {
    private val mutex = Mutex()
    private const val MANIFEST = "installation.json"
    fun availableModels(context: Context): List<LocalSpeechModelDescriptor> = LocalSpeechRegistry.catalog(context)
    fun selectedId(context: Context): String {
        mainProcess(context)
        return context.getSharedPreferences("local_speech_selection", Context.MODE_PRIVATE)
            .getString("model_id", LocalSpeechRegistry.DEFAULT_ID) ?: LocalSpeechRegistry.DEFAULT_ID
    }
    fun selectModel(context: Context, modelId: String) {
        mainProcess(context)
        val model = LocalSpeechRegistry.model(context, modelId)
        if (!model.available) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, model.availabilityReason ?: "此本机模型尚不可用")
        check(context.getSharedPreferences("local_speech_selection", Context.MODE_PRIVATE).edit().putString("model_id", modelId).commit())
    }
    private fun mainProcess(context: Context) {
        check(Application.getProcessName() == context.packageName) { "模型选择偏好只能在主进程访问" }
    }
    internal fun root(context: Context): File {
        val parent = context.filesDir.canonicalFile
        val root = File(parent, "local_speech_models")
        require(root.canonicalPath == root.absolutePath) { "模型路径不是安全目录" }
        check(root.isDirectory || root.mkdir()) { "无法创建模型目录，请检查存储空间" }
        return root
    }
    internal fun directory(context: Context, id: String): File {
        require(id.matches(Regex("[a-z0-9_]{1,80}")))
        return File(root(context), id).also { require(it.canonicalPath == it.absolutePath) { "模型目录不能是符号链接" } }
    }
    private suspend fun <T> locked(context: Context, id: String, action: suspend () -> T): T = mutex.withLock {
        LocalSpeechRegistry.model(context, id)
        val locks = File(root(context), ".locks")
        require(locks.canonicalPath == locks.absolutePath)
        check(locks.isDirectory || locks.mkdir())
        val lockFile = File(locks, "$id.lock"); require(lockFile.canonicalPath == lockFile.absolutePath)
        RandomAccessFile(lockFile, "rw").use { file ->
            var lock: java.nio.channels.FileLock? = null
            try {
                while (lock == null) {
                    currentCoroutineContext().ensureActive()
                    lock = try { file.channel.tryLock() } catch (_: OverlappingFileLockException) { null }
                    if (lock == null) delay(50)
                }
                action()
            } finally { lock?.release() }
        }
    }
    suspend fun inspect(context: Context, modelId: String = selectedId(context)): LocalSpeechInstallation = withContext(Dispatchers.IO) {
        try {
            locked(context, modelId) {
                val m = LocalSpeechRegistry.model(context, modelId)
                val dir = directory(context, modelId)
                if (!dir.exists()) LocalSpeechInstallation(modelId, false, "尚未安装，请下载或导入所选模型")
                else { verify(context, m, dir) {}; LocalSpeechInstallation(modelId, true, "模型校验通过，可完全离线使用") }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { LocalSpeechInstallation(modelId, false, "模型文件缺失、校验失败或目录不安全；现有文件已保留，请检查后手动处理") }
    }
    suspend fun download(context: Context, modelId: String,
        onProgress: (LocalSpeechProgress) -> Unit = {}): LocalSpeechInstallation = install(context, modelId, onProgress) { model, stage ->
        LocalSpeechDownload.fetch(model, stage, onProgress)
    }
    suspend fun `import`(context: Context, modelId: String, opener: () -> InputStream,
        onProgress: (LocalSpeechProgress) -> Unit = {}): LocalSpeechInstallation = install(context, modelId, onProgress) { model, stage ->
        LocalSpeechImport.extract(model, stage, opener, onProgress)
    }
    suspend fun importModel(context: Context, modelId: String, opener: () -> InputStream,
        onProgress: (LocalSpeechProgress) -> Unit = {}): LocalSpeechInstallation = `import`(context, modelId, opener, onProgress)

    private suspend fun install(context: Context, id: String, progress: (LocalSpeechProgress) -> Unit,
        populate: suspend (LocalSpeechModelDescriptor, File) -> Unit): LocalSpeechInstallation = withContext(Dispatchers.IO) {
        try { locked(context, id) {
            val model = LocalSpeechRegistry.model(context, id)
            if (!model.available) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, model.availabilityReason ?: "该模型尚未通过验证")
            val target = directory(context, id)
            if (target.exists()) {
                try { verify(context, model, target) {} }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { throw SpeechFailure(SpeechErrorCode.STORAGE, "已有模型目录校验失败，文件已保留；不会覆盖或删除，请先手动处理") }
                return@locked LocalSpeechInstallation(id, true, "现有模型校验通过，无需重复安装")
            }
            val stage = File(root(context), ".stage-$id-${UUID.randomUUID()}")
            check(stage.mkdir()) { "无法创建临时目录" }
            try {
                populate(model, stage)
                writeMetadata(context, model, stage)
                verify(context, model, stage, progress)
                currentCoroutineContext().ensureActive()
                progress(LocalSpeechProgress(LocalSpeechStage.INSTALLING, 0, model.sizeBytes))
                require(!target.exists() && target.canonicalPath == target.absolutePath)
                check(stage.renameTo(target)) { "模型原子安装失败，原有模型未修改" }
                // No suspension between rename and success; cancellation only ever cleans this operation's own stage.
                progress(LocalSpeechProgress(LocalSpeechStage.COMPLETE, model.sizeBytes, model.sizeBytes))
                LocalSpeechInstallation(id, true, "模型安装并校验完成")
            } finally { if (stage.exists()) stage.deleteRecursively() }
        } } catch (e: CancellationException) { throw e }
        catch (e: SpeechFailure) { throw e }
        catch (e: IllegalArgumentException) { throw SpeechFailure(SpeechErrorCode.CONFIGURATION,
            e.message?.takeIf { text -> text.any { it in '\u3400'..'\u9fff' } }?.take(220)
                ?: "模型格式或校验不匹配，请使用目录指定文件；不支持原始 safetensors") }
        catch (_: SecurityException) { throw SpeechFailure(SpeechErrorCode.PERMISSION, "无法读取所选模型文件，请重新通过文件选择器授权导入") }
        catch (_: Exception) { throw SpeechFailure(SpeechErrorCode.STORAGE, "模型安装失败，请检查存储空间及文件权限；已有模型文件未删除") }
    }
    private fun manifest(model: LocalSpeechModelDescriptor) = LocalSpeechManifest.encode(model)
    private fun writeMetadata(context: Context, model: LocalSpeechModelDescriptor, dir: File) {
        fun write(name: String, bytes: ByteArray) { File(dir, name).outputStream().use { it.write(bytes); it.fd.sync() } }
        write(MANIFEST, manifest(model).toString().toByteArray(Charsets.UTF_8))
        write("LICENSE.txt", context.assets.open(model.licenseAsset).use { it.readBytesBounded(131_072) })
        write("catalog-entry.json", manifest(model).put("displayName", model.displayName).put("description", model.description).toString().toByteArray(Charsets.UTF_8))
    }
    private fun checkedFile(dir: File, name: String): File = File(dir, name).also {
        require(it.canonicalPath == File(dir.canonicalFile, name).absolutePath && it.isFile) { "模型文件缺失或路径不安全" }
        require(Os.lstat(it.absolutePath).st_nlink == 1L) { "模型文件不能使用硬链接" }
    }
    private fun readManifest(dir: File, model: LocalSpeechModelDescriptor): String {
        val file = checkedFile(dir, MANIFEST); require(file.length() in 1..65_536L)
        val text = file.readText(Charsets.UTF_8)
        LocalSpeechManifest.validate(text, model)
        return text
    }
    private suspend fun verify(context: Context, model: LocalSpeechModelDescriptor, dir: File,
        progress: (LocalSpeechProgress) -> Unit) {
        require(dir.isDirectory && dir.canonicalPath == dir.absolutePath)
        readManifest(dir, model)
        val coroutine = currentCoroutineContext(); var completed = 0L
        progress(LocalSpeechProgress(LocalSpeechStage.VERIFYING, 0, model.sizeBytes))
        for (entry in model.files) {
            coroutine.ensureActive(); val file = checkedFile(dir, entry.name)
            require(file.length() == entry.sizeBytes) { "模型大小与目录不匹配" }
            val base = completed
            val hash = LocalSpeechValidation.sha256(file, { coroutine.ensureActive() }) { bytes ->
                progress(LocalSpeechProgress(LocalSpeechStage.VERIFYING, base + bytes, model.sizeBytes))
            }
            require(hash == entry.sha256) { "模型 SHA-256 不匹配，请选择所选模型对应的完整文件" }
            when { entry.name.endsWith(".gguf") -> LocalSpeechValidation.gguf(file, model.architecture)
                entry.name == "tokens.txt" -> LocalSpeechValidation.tokens(file)
                entry.name.endsWith(".onnx") -> LocalSpeechValidation.onnx(file) }
            completed += entry.sizeBytes
        }
        val license = checkedFile(dir, "LICENSE.txt")
        val expectedLicense = context.assets.open(model.licenseAsset).use { it.readBytesBounded(131_072) }
        require(license.length() == expectedLicense.size.toLong() && license.readBytes().contentEquals(expectedLicense)) { "模型许可元数据不匹配" }
    }
    internal data class FileIdentity(val name: String, val size: Long, val device: Long, val inode: Long,
        val modified: Long, val changed: Long, val modifiedNanos: Long, val changedNanos: Long)
    internal data class ModelIdentity(val id: String, val manifest: String, val files: List<FileIdentity>)
    internal fun quickFingerprint(context: Context, modelId: String): ModelIdentity {
        val model = LocalSpeechRegistry.model(context, modelId); val dir = directory(context, modelId)
        val marker = readManifest(dir, model)
        fun identity(file: File): FileIdentity {
            require(file.canonicalPath == file.absolutePath)
            val stat = Os.lstat(file.absolutePath)
            return FileIdentity(file.name, stat.st_size, stat.st_dev, stat.st_ino, stat.st_mtime, stat.st_ctime,
                stat.st_mtim.tv_nsec, stat.st_ctim.tv_nsec)
        }
        return ModelIdentity(modelId, marker, (listOf(dir, checkedFile(dir, MANIFEST), checkedFile(dir, "LICENSE.txt")) +
            model.files.map { checkedFile(dir, it.name) }).map(::identity))
    }
    internal suspend fun <T : Any> loadVerified(context: Context, modelId: String, release: (T) -> Unit,
        onVerified: (Long) -> Unit = {}, load: (File, LocalSpeechModelDescriptor) -> T): Pair<ModelIdentity, T> = locked(context, modelId) {
        val model = LocalSpeechRegistry.model(context, modelId)
        if (!model.available) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "所选模型当前不可用，请在本机离线设置中检查")
        val dir = directory(context, modelId)
        if (!dir.exists()) throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "尚未安装所选本机模型，请先下载或导入；不会自动切换模型")
        val before = quickFingerprint(context, modelId); val start = SystemClock.elapsedRealtime()
        try { verify(context, model, dir) {} }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { throw SpeechFailure(SpeechErrorCode.CONFIGURATION, "模型完整性校验失败，文件已保留；请在本机离线设置中检查") }
        onVerified(SystemClock.elapsedRealtime() - start)
        check(before == quickFingerprint(context, modelId)) { "模型在校验时发生变化" }
        val resource = load(dir, model)
        try { check(before == quickFingerprint(context, modelId)) { "模型在加载时发生变化" }; before to resource }
        catch (e: Throwable) { release(resource); throw e }
    }
}
