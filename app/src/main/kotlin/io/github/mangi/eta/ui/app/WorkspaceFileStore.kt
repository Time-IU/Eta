package io.github.mangi.eta.ui.app

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.terminal.FileToolBackend
import io.github.mangi.eta.agent.terminal.FileToolKind
import io.github.mangi.eta.agent.terminal.LocalFileToolBackend
import io.github.mangi.eta.agent.terminal.ShellFileToolBackend
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.agent.terminal.TerminalRuntime
import io.github.mangi.eta.agent.terminal.runOneShotShell
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class WorkspaceEntry(val path: String, val name: String, val directory: Boolean, val size: Long)

/** 浏览器内联预览的文件大小上限：超过就不读全量，交给外部应用打开。 */
internal const val WORKSPACE_PREVIEW_MAX_BYTES: Long = 2L * 1024 * 1024

internal class WorkspaceFileStore(
    context: Context,
    private val backendFor: (File) -> FileToolBackend = ::workspaceBackend,
) {
    private val appContext = context.applicationContext

    /**
     * 浏览的根目录：root 工作区（/data/local/tmp/eta，Linux 侧的 /workspace）。
     *
     * Root 可用时浏览共享工作区；未授权时保留原有的 App 私有工作区。
     * 后端仍可处理 App UID 无法直接访问的文件，不假定 Android 权限。
     */
    private val root: File = (if (RootAccess.isGranted) File(TerminalRuntime.ROOT_WORKSPACE_PATH)
        else TerminalPrivateStorage.workspace(appContext.filesDir)).canonicalFile

    /**
     * backend 只建一次。每次 new ShellProcessSupervisor 都会新起一条 shell 进程，
     * 而 list() 是按目录逐个调的，根目录 + 每个展开目录各起一次进程，页面就会明显卡。
     */
    private val backend: FileToolBackend by lazy { backendFor(root) }

    /** Direct listing is fast when permitted; paged backend remains the permission-safe fallback. */
    suspend fun list(path: String): List<WorkspaceEntry> = withContext(Dispatchers.IO) {
        ensureActive()
        val directory = resolveFile(path)
        val children = directory.listFiles() ?: return@withContext listWithBackend(path)
        children.asSequence()
            // 符号链接不跟随：跟随可能绕出工作区，也可能在循环链接上打转。
            .filter { !java.nio.file.Files.isSymbolicLink(it.toPath()) && (it.isFile || it.isDirectory) }
            .map { child ->
                WorkspaceEntry(
                    path = child.relativeTo(root).path,
                    name = child.name,
                    directory = child.isDirectory,
                    size = if (child.isDirectory) 0L else child.length(),
                )
            }
            .sortedWith(compareByDescending<WorkspaceEntry> { it.directory }.thenBy { it.name.lowercase() })
            .toList()
    }

    private suspend fun listWithBackend(path: String): List<WorkspaceEntry> {
        val entries = mutableListOf<WorkspaceEntry>()
        val directory = resolve(backend, path)
        var offset = 0
        var revision: String? = null
        do {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val page = backend.list(directory, offset, 200, revision)
            revision = page.revision
            page.entries.filter { it.kind == FileToolKind.FILE || it.kind == FileToolKind.DIRECTORY }.forEach { entry ->
                if (File(entry.path).toPath().startsWith(root.toPath())) entries += WorkspaceEntry(
                    File(entry.path).relativeTo(root).path, entry.name, entry.kind == FileToolKind.DIRECTORY, entry.sizeBytes,
                )
            }
            if (!page.hasMore) break
            check(page.nextOffset > offset) { "WORKSPACE_INVALID_PAGE" }
            offset = page.nextOffset
        } while (true)
        return entries.sortedWith(compareByDescending<WorkspaceEntry> { it.directory }.thenBy { it.name.lowercase() })
    }

    /** 把相对路径解析成 root 内的 File，并校验没有越出 root。 */
    /** 相对路径 → 绝对路径。图片预览这类需要真实路径的地方用。 */
    fun absolutePath(path: String): String = resolveFile(path).absolutePath

    private fun resolveFile(path: String): File {
        val candidate = if (path.isBlank()) root else File(root, path)
        val canonical = candidate.canonicalFile
        require(canonical.toPath().startsWith(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
        return canonical
    }

    /** Preview is bounded even if a file grows after the initial stat. */
    suspend fun readText(path: String): String = withContext(Dispatchers.IO) {
        val source = resolve(backend, path)
        val stat = backend.stat(source)
        if (stat.kind != FileToolKind.FILE) throw java.io.IOException("WORKSPACE_NOT_FILE")
        if (stat.sizeBytes > WORKSPACE_PREVIEW_MAX_BYTES) throw java.io.IOException("WORKSPACE_FILE_TOO_LARGE")
        val file = resolveFile(path)
        if (file.canRead()) {
            val bytes = file.inputStream().use { it.readNBytes((WORKSPACE_PREVIEW_MAX_BYTES + 1).toInt()) }
            if (bytes.size > WORKSPACE_PREVIEW_MAX_BYTES) throw java.io.IOException("WORKSPACE_FILE_TOO_LARGE")
            bytes.toString(Charsets.UTF_8)
        } else {
            val bytes = java.io.ByteArrayOutputStream()
            var offset = 0L
            while (offset < stat.sizeBytes) {
                ensureActive()
                val chunk = backend.readBytes(source, offset, minOf(16 * 1024L, stat.sizeBytes - offset).toInt())
                check(chunk.isNotEmpty()) { "WORKSPACE_FILE_CHANGED" }
                bytes.write(chunk)
                offset += chunk.size
                check(offset <= WORKSPACE_PREVIEW_MAX_BYTES) { "WORKSPACE_FILE_TOO_LARGE" }
            }
            bytes.toByteArray().toString(Charsets.UTF_8)
        }
    }

    /** Binary copies retain backend access and cancellation when the app UID cannot read directly. */
    suspend fun copyTo(path: String, destination: File): Long = withContext(Dispatchers.IO) {
        val source = resolve(backend, path)
        val stat = backend.stat(source)
        if (stat.kind != FileToolKind.FILE) throw java.io.IOException("WORKSPACE_NOT_FILE")
        destination.parentFile?.mkdirs()
        destination.outputStream().use { output ->
            val file = resolveFile(path)
            if (file.canRead()) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            } else {
                var offset = 0L
                while (offset < stat.sizeBytes) {
                    ensureActive()
                    val chunk = backend.readBytes(source, offset, minOf(16 * 1024L, stat.sizeBytes - offset).toInt())
                    check(chunk.isNotEmpty()) { "WORKSPACE_FILE_CHANGED" }
                    output.write(chunk)
                    offset += chunk.size
                }
            }
        }
        destination.length()
    }

    suspend fun importFile(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        AgentFileReferenceGateway.importDocumentUri(appContext, uri) is AgentFileReferenceGateway.Resolution.Success
    }

    suspend fun exportFile(path: String, destination: Uri) = withContext(Dispatchers.IO) {
        val source = resolve(backend, path)
        val stat = backend.stat(source)
        check(stat.kind == FileToolKind.FILE) { "WORKSPACE_NOT_FILE" }
        val output = appContext.contentResolver.openOutputStream(destination, "wt")
            ?: throw java.io.IOException("EXPORT_UNAVAILABLE")
        output.use {
            val file = File(source)
            if (file.canRead()) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            } else {
                var offset = 0L
                while (offset < stat.sizeBytes) {
                    ensureActive()
                    val bytes = backend.readBytes(source, offset, 16 * 1024)
                    check(bytes.isNotEmpty()) { "WORKSPACE_FILE_CHANGED" }
                    output.write(bytes)
                    offset += bytes.size
                }
            }
        }
    }

    private fun resolve(backend: FileToolBackend, path: String): String = backend.resolve(path).also {
        require(File(it).toPath().startsWith(root.toPath())) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
    }
}

private fun workspaceBackend(root: File): FileToolBackend {
    if (!RootAccess.isGranted) return LocalFileToolBackend(root)
    val supervisor = ShellProcessSupervisor()
    return ShellFileToolBackend("android", "root", root.path, root.path) { command, input, limit ->
        runOneShotShell(supervisor, "root", command, timeoutSeconds = 20, stdin = input, maxOutputBytes = limit)
    }
}

/** 预览用读取：失败返回 null（不抛给 UI）。 */
internal suspend fun readWorkspaceText(store: WorkspaceFileStore, relativePath: String): String? =
    try {
        store.readText(relativePath)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
