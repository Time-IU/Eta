package io.github.mangi.eta.agent.skill

import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** ZIP extraction and structural validation; no Android, Room, or assets dependencies. */
internal class SkillArchiveExtractor(private val limits: SkillPackageLimits) {
    fun extract(archiveFile: File, targetRoot: File, isCancelled: () -> Boolean = { false }) {
        if (!targetRoot.mkdir()) fail(SkillInstallErrorCode.IO_ERROR, "无法创建 ZIP 暂存目录")
        val pathKinds = linkedMapOf<String, Boolean>()
        var entryCount = 0
        var extractedBytes = 0L
        ZipInputStream(archiveFile.inputStream().buffered()).use { zip ->
            while (true) {
                checkCancelled(isCancelled)
                val entry = zip.nextEntry ?: break
                entryCount++
                if (entryCount > limits.maxEntries) {
                    fail(SkillInstallErrorCode.TOO_MANY_ENTRIES, "ZIP 条目数超过 ${limits.maxEntries} 个")
                }
                val normalizedName = entry.name.removeSuffix("/")
                val segments = SkillPackagePolicy.validateRelativePath(normalizedName)
                    ?: fail(SkillInstallErrorCode.UNSAFE_ENTRY_PATH, "路径不是安全的相对路径")
                if (segments.size > limits.maxPathDepth) {
                    fail(SkillInstallErrorCode.ENTRY_PATH_TOO_DEEP, "ZIP 条目路径层级超过 ${limits.maxPathDepth} 层")
                }
                val relativePath = segments.joinToString("/")
                val collisionKey = SkillPackagePolicy.collisionKey(relativePath)
                if (pathKinds.containsKey(collisionKey)) {
                    fail(SkillInstallErrorCode.DUPLICATE_ENTRY, "ZIP 包含重复条目：$relativePath")
                }
                val ancestorKeys = segments.indices.drop(1).map { index ->
                    SkillPackagePolicy.collisionKey(segments.take(index).joinToString("/"))
                }
                if (ancestorKeys.any { pathKinds[it] == false }) {
                    fail(SkillInstallErrorCode.DUPLICATE_ENTRY, "ZIP 条目存在文件与目录冲突：$relativePath")
                }
                if (!entry.isDirectory && pathKinds.keys.any { it.startsWith("$collisionKey/") }) {
                    fail(SkillInstallErrorCode.DUPLICATE_ENTRY, "ZIP 条目存在文件与目录冲突：$relativePath")
                }
                pathKinds[collisionKey] = entry.isDirectory

                val target = File(targetRoot, relativePath)
                ensureWithin(targetRoot, target)
                if (entry.isDirectory) {
                    if (!target.mkdirs() && !target.isDirectory) fail(SkillInstallErrorCode.IO_ERROR, "无法创建 ZIP 目录")
                    if (zip.read() != -1) fail(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP 目录条目包含数据")
                } else {
                    if (entry.size > limits.maxSingleFileBytes) {
                        fail(SkillInstallErrorCode.ENTRY_TOO_LARGE, "ZIP 单个文件超过 ${limits.maxSingleFileBytes} 字节限制")
                    }
                    target.parentFile?.let { parent ->
                        if (!parent.mkdirs() && !parent.isDirectory) fail(SkillInstallErrorCode.IO_ERROR, "无法创建 ZIP 文件目录")
                    }
                    var fileBytes = 0L
                    target.outputStream().buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            checkCancelled(isCancelled)
                            val count = zip.read(buffer)
                            if (count < 0) break
                            fileBytes += count
                            extractedBytes += count
                            if (fileBytes > limits.maxSingleFileBytes) {
                                fail(SkillInstallErrorCode.ENTRY_TOO_LARGE, "ZIP 单个文件超过 ${limits.maxSingleFileBytes} 字节限制")
                            }
                            if (extractedBytes > limits.maxExtractedBytes) {
                                fail(SkillInstallErrorCode.EXTRACTED_CONTENT_TOO_LARGE, "ZIP 解压内容超过 ${limits.maxExtractedBytes} 字节限制")
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        if (entryCount == 0) fail(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP 为空或格式无效")
    }

    private fun ensureWithin(root: File, target: File) {
        val rootPath = root.canonicalFile.toPath()
        val targetPath = target.canonicalFile.toPath()
        if (!targetPath.startsWith(rootPath) || targetPath == rootPath) {
            fail(SkillInstallErrorCode.UNSAFE_ENTRY_PATH, "ZIP 条目试图写出暂存目录")
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) fail(SkillInstallErrorCode.CANCELLED, "Skill 安装已取消")
    }

    private fun fail(code: SkillInstallErrorCode, message: String): Nothing =
        throw SkillArchiveExtractionException(SkillInstallError(code, message))
}

internal class SkillArchiveExtractionException(val error: SkillInstallError) : IOException(error.message)
