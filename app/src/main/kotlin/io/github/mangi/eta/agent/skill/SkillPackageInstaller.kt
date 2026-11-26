package io.github.mangi.eta.agent.skill

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.UUID
import java.util.zip.ZipException

/**
 * Skill ZIP 安装器。
 *
 * 所有内容先落入 skills 目录外的私有暂存区，完成路径、配额和 Skill 元数据验证后才会
 * 移入正式目录。批量替换会先备份旧目录；任一步失败都会删除新目录并恢复备份。
 */
class SkillPackageInstaller internal constructor(
    skillsRoot: File,
    private val indexService: SkillIndexService,
    private val limits: SkillPackageLimits = SkillPackageLimits(),
    private val directoryMover: SkillDirectoryMover = AtomicSkillDirectoryMover,
    private val builtinIdLookup: (String) -> Boolean = indexService::isBuiltinSkillId,
) {
    private val canonicalSkillsRoot = skillsRoot.canonicalFile
    private val workRoot = File(
        requireNotNull(canonicalSkillsRoot.parentFile) { "Skills 目录必须有父目录" },
        ".eta-skill-installer",
    )

    fun installLocalZip(
        openStream: () -> InputStream,
        replaceUserSkill: Boolean = false,
        expectedReplacementId: String? = null,
        expectedArchiveSha256: String? = null,
        isCancelled: () -> Boolean = { false },
    ): SkillInstallResult = withArchive(openStream, isCancelled) { operation, extractedRoot ->
        val candidates = discoverCandidates(extractedRoot, extractedRoot, isCancelled)
        when {
            candidates.isEmpty() -> fail(
                SkillInstallErrorCode.NO_SKILL_FOUND,
                "ZIP 中没有找到 SKILL.md",
            )
            candidates.size > 1 -> fail(
                SkillInstallErrorCode.MULTIPLE_SKILLS_FOUND,
                "本地 ZIP 必须只包含一个 Skill，当前发现 ${candidates.size} 个",
            )
        }
        if (replaceUserSkill) {
            val expectedId = expectedReplacementId?.trim().orEmpty()
            val expectedDigest = expectedArchiveSha256?.trim().orEmpty()
            if (expectedId.isBlank()) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "替换用户 Skill 时必须指定已确认的 Skill id",
                )
            }
            if (candidates.single().id != expectedId) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "重新读取的 ZIP 与已确认替换的 Skill 不一致",
                )
            }
            if (!SHA_256_REGEX.matches(expectedDigest)) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "替换用户 Skill 时必须提供有效的小写 SHA-256",
                )
            }
            if (operation.archiveSha256 != expectedDigest) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "重新读取的 ZIP 内容与已确认归档不一致",
                )
            }
        } else if (expectedReplacementId != null || expectedArchiveSha256 != null) {
            fail(
                SkillInstallErrorCode.INVALID_SELECTION,
                "仅替换现有用户 Skill 时可指定替换身份与归档摘要",
            )
        }
        installCandidates(
            operation = operation,
            candidates = candidates,
            replaceUserSkills = replaceUserSkill,
            isCancelled = isCancelled,
            conflictArchiveSha256 = operation.archiveSha256,
        )
    }

    /** 检查仓库 ZIP。单一顶层目录按 GitHub codeload 的 envelope 处理。 */
    fun inspectRepositoryZip(
        openStream: () -> InputStream,
        isCancelled: () -> Boolean = { false },
    ): SkillArchiveInspectionResult = withArchiveInspection(openStream, isCancelled) { extractedRoot ->
        val repositoryRoot = repositoryContentRoot(extractedRoot)
        val candidates = discoverCandidates(repositoryRoot, repositoryRoot, isCancelled)
        if (candidates.isEmpty()) {
            fail(SkillInstallErrorCode.NO_SKILL_FOUND, "仓库 ZIP 中没有找到 SKILL.md")
        }
        SkillArchiveInspectionResult.Success(candidates.map { it.publicModel })
    }

    /** selectedPaths 使用 [inspectRepositoryZip] 返回的仓库相对路径。 */
    fun installRepositoryZip(
        openStream: () -> InputStream,
        selectedPaths: List<String>,
        replaceUserSkills: Boolean = false,
        expectedReplacementIds: Set<String> = emptySet(),
        enabledById: Map<String, Boolean> = emptyMap(),
        isCancelled: () -> Boolean = { false },
    ): SkillInstallResult {
        val selectedPathsSnapshot = selectedPaths.toList()
        val expectedIdsSnapshot = expectedReplacementIds.toSet()
        val enabledSnapshot = enabledById.toMap()
        return withArchive(openStream, isCancelled) { operation, extractedRoot ->
            if (selectedPathsSnapshot.isEmpty()) {
                fail(SkillInstallErrorCode.INVALID_SELECTION, "至少选择一个 Skill 路径")
            }
            val repositoryRoot = repositoryContentRoot(extractedRoot)
            val allCandidates = discoverCandidates(repositoryRoot, repositoryRoot, isCancelled)
            if (allCandidates.isEmpty()) {
                fail(SkillInstallErrorCode.NO_SKILL_FOUND, "仓库 ZIP 中没有找到 SKILL.md")
            }
            val candidatesByPath = allCandidates.associateBy { it.relativePath }
            val normalizedSelections = selectedPathsSnapshot.map(::normalizeSelectionPath)
            if (normalizedSelections.distinct().size != normalizedSelections.size) {
                fail(SkillInstallErrorCode.INVALID_SELECTION, "选择中包含重复的 Skill 路径")
            }
            val selected = normalizedSelections.map { relativePath ->
                candidatesByPath[relativePath]
                    ?: fail(
                        SkillInstallErrorCode.INVALID_SELECTION,
                        "所选路径不是有效的 Skill 根目录：$relativePath",
                    )
            }
            rejectNestedCandidateSelections(selected, allCandidates)
            val selectedIds = selected.mapTo(linkedSetOf()) { it.id }
            if (enabledSnapshot.keys.any { it !in selectedIds }) {
                fail(SkillInstallErrorCode.INVALID_SELECTION, "enabled state includes an unselected Skill")
            }
            if (replaceUserSkills) {
                if (expectedIdsSnapshot.isEmpty() || selectedIds != expectedIdsSnapshot) {
                    fail(
                        SkillInstallErrorCode.INVALID_SELECTION,
                        "重新读取的仓库 Skill 与已确认替换的 Skill 集合不一致",
                    )
                }
            } else if (expectedIdsSnapshot.isNotEmpty()) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "仅替换现有用户 Skill 时可指定 expectedReplacementIds",
                )
            }
            installCandidates(
                operation = operation,
                candidates = selected,
                replaceUserSkills = replaceUserSkills,
                enabledById = enabledSnapshot,
                isCancelled = isCancelled,
                conflictArchiveSha256 = null,
            )
        }
    }

    private fun installCandidates(
        operation: ArchiveOperation,
        candidates: List<PreparedCandidate>,
        replaceUserSkills: Boolean,
        enabledById: Map<String, Boolean> = emptyMap(),
        isCancelled: () -> Boolean,
        conflictArchiveSha256: String?,
    ): SkillInstallResult = indexService.withMutationLock {
        checkCancelled(isCancelled)
        installCandidatesLocked(
            operation,
            candidates,
            replaceUserSkills,
            enabledById,
            isCancelled,
            conflictArchiveSha256,
        )
    }

    private fun installCandidatesLocked(
        operation: ArchiveOperation,
        candidates: List<PreparedCandidate>,
        replaceUserSkills: Boolean,
        enabledById: Map<String, Boolean>,
        isCancelled: () -> Boolean,
        conflictArchiveSha256: String?,
    ): SkillInstallResult {
        val duplicateIds = candidates.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicateIds.isNotEmpty()) {
            fail(
                SkillInstallErrorCode.DUPLICATE_SKILL_ID,
                "所选 Skill 使用了重复名称：${duplicateIds.sorted().joinToString()}",
            )
        }

        val installedEntries = indexService.listSkillsForManagement(forceRefresh = true)
            .associateBy { it.id }
        val conflicts = mutableListOf<SkillInstallConflict>()
        candidates.forEach { candidate ->
            val target = File(canonicalSkillsRoot, candidate.id)
            val existing = installedEntries[candidate.id]
            when {
                builtinIdLookup(candidate.id) -> conflicts += SkillInstallConflict(
                    id = candidate.id,
                    name = candidate.name,
                    existingSource = BUILTIN_SKILL_SOURCE,
                    replaceAllowed = false,
                )
                existing != null && !replaceUserSkills -> conflicts += SkillInstallConflict(
                    id = candidate.id,
                    name = candidate.name,
                    existingSource = existing.source,
                    replaceAllowed = existing.source == USER_SKILL_SOURCE &&
                        isReplaceableTarget(existing, target),
                )
                existing != null && !isReplaceableTarget(existing, target) -> conflicts +=
                    SkillInstallConflict(
                        id = candidate.id,
                        name = candidate.name,
                        existingSource = existing.source,
                        replaceAllowed = false,
                    )
                existing == null && target.exists() -> conflicts += SkillInstallConflict(
                    id = candidate.id,
                    name = candidate.name,
                    existingSource = "unknown",
                    replaceAllowed = false,
                )
            }
        }
        if (conflicts.isNotEmpty()) {
            return SkillInstallResult.Conflict(
                conflicts = conflicts.sortedBy { it.id },
                archiveSha256 = conflictArchiveSha256,
            )
        }

        // 从此处开始进入不可中断的短事务；取消只在任何正式文件变更发生前生效。
        checkCancelled(isCancelled)
        if (!canonicalSkillsRoot.exists() && !canonicalSkillsRoot.mkdirs()) {
            fail(SkillInstallErrorCode.IO_ERROR, "无法创建 Skills 目录")
        }
        val backupRoot = File(operation.directory, "backup")
        if (!backupRoot.mkdir()) {
            fail(SkillInstallErrorCode.IO_ERROR, "无法创建安装备份目录")
        }
        val registrySnapshots = indexService
            .captureRegistryRecoverySnapshots(candidates.map { it.id })
            .associateBy { it.skillId }
        var recoveryJournal: PendingSkillRecoveryJournal? = null
        try {
            recoveryJournal = PendingSkillRecoveryJournal.begin(
                skillsRoot = canonicalSkillsRoot,
                operationDirectory = operation.directory,
                records = candidates.map { candidate ->
                    SkillRecoveryRecord(
                        id = candidate.id,
                        originalTargetExisted = Files.exists(
                            File(canonicalSkillsRoot, candidate.id).toPath(),
                            LinkOption.NOFOLLOW_LINKS,
                        ),
                        registrySnapshot = checkNotNull(registrySnapshots[candidate.id]),
                    )
                },
            )
            candidates.forEach { candidate ->
                val target = File(canonicalSkillsRoot, candidate.id)
                if (target.exists()) {
                    val backup = File(backupRoot, candidate.id)
                    directoryMover.move(target, backup)
                    recoveryJournal.markBackupCompleted(candidate.id)
                }
            }
            candidates.forEach { candidate ->
                val target = File(canonicalSkillsRoot, candidate.id)
                directoryMover.move(candidate.directory, target)
                recoveryJournal.markNewTargetCommitted(candidate.id)
            }
            indexService.registerInstalledUserSkills(candidates.map { it.id }, enabledById)
            recoveryJournal.clear()
        } catch (error: Exception) {
            val journalExists = Files.exists(
                File(operation.directory, JOURNAL_FILE_NAME).toPath(),
                LinkOption.NOFOLLOW_LINKS,
            )
            val rollbackComplete = !journalExists || runCatching {
                val recovered = recoverPendingSkillOperations(canonicalSkillsRoot, directoryMover)
                indexService.restoreRecoveredRegistry(recovered)
                completeRecoveredSkillOperations(canonicalSkillsRoot, recovered)
            }.isSuccess
            if (!rollbackComplete) operation.preserveForRecovery = true
            val suffix = if (rollbackComplete) "，旧 Skill 已恢复" else "，且自动恢复未完整完成"
            return SkillInstallResult.Failure(
                SkillInstallError(
                    code = SkillInstallErrorCode.COMMIT_FAILED,
                    message = "Skill 安装提交失败$suffix",
                ),
                recoveryRequired = !rollbackComplete,
            )
        }

        return SkillInstallResult.Success(
            installed = candidates.map {
                InstalledSkill(id = it.id, name = it.name, description = it.description)
            }
        )
    }

    private fun isReplaceableTarget(entry: SkillIndexEntry, expectedTarget: File): Boolean {
        if (entry.source != USER_SKILL_SOURCE || !entry.installed) return false
        val existingRoot = runCatching { File(entry.rootPath).canonicalFile }.getOrNull() ?: return false
        if (existingRoot != expectedTarget.canonicalFile || !existingRoot.isDirectory) return false
        return isRecoverableSkillDirectoryTree(canonicalSkillsRoot, existingRoot)
    }

    private fun rejectNestedCandidateSelections(
        selected: List<PreparedCandidate>,
        allCandidates: List<PreparedCandidate>,
    ) {
        selected.forEach { parent ->
            val nested = allCandidates.firstOrNull { candidate ->
                candidate !== parent && candidate.directory.toPath().startsWith(parent.directory.toPath())
            }
            if (nested != null) {
                fail(
                    SkillInstallErrorCode.INVALID_SELECTION,
                    "Skill ${parent.relativePath} 内还包含另一个 SKILL.md：${nested.relativePath}",
                )
            }
        }
    }

    private fun discoverCandidates(
        scanRoot: File,
        relativeRoot: File,
        isCancelled: () -> Boolean,
    ): List<PreparedCandidate> {
        val skillFiles = scanRoot.walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && it.name == SKILL_FILE_NAME }
            .toList()
        return skillFiles.map { skillFile ->
            checkCancelled(isCancelled)
            val directory = requireNotNull(skillFile.parentFile).canonicalFile
            val relativePath = directory.relativeTo(relativeRoot.canonicalFile)
                .invariantSeparatorsPath
                .ifBlank { "." }
            val metadata = parseAndValidateSkill(skillFile)
            PreparedCandidate(
                id = metadata.name,
                name = metadata.name,
                description = metadata.description,
                relativePath = relativePath,
                directory = directory,
            )
        }.sortedBy { it.relativePath }
    }

    private fun parseAndValidateSkill(skillFile: File): ValidatedSkillMetadata {
        if (skillFile.length() > limits.maxSkillFileBytes) {
            fail(
                SkillInstallErrorCode.INVALID_SKILL,
                "${skillFile.name} 超过 ${limits.maxSkillFileBytes} 字节限制",
            )
        }
        val raw = readStrictUtf8(skillFile, limits.maxSkillFileBytes)
            ?: fail(SkillInstallErrorCode.INVALID_SKILL, "SKILL.md 必须是 UTF-8 文本")
        val metadata = SkillPackagePolicy.validateSkillMetadata(
            raw,
            maxNameLength = MAX_SKILL_NAME_LENGTH,
            maxDescriptionLength = MAX_SKILL_DESCRIPTION_LENGTH,
        ) ?: fail(
            SkillInstallErrorCode.INVALID_SKILL,
            "Skill name 或 description 不符合格式与长度要求",
        )
        return ValidatedSkillMetadata(name = metadata.name, description = metadata.description)
    }

    private fun repositoryContentRoot(extractedRoot: File): File {
        val children = extractedRoot.listFiles().orEmpty()
        return children.singleOrNull()?.takeIf { it.isDirectory } ?: extractedRoot
    }

    private fun normalizeSelectionPath(raw: String): String {
        val value = raw.trim().removeSuffix("/")
        if (value == ".") return value
        return SkillPackagePolicy.normalizeSelectionPath(value)
            ?: fail(SkillInstallErrorCode.INVALID_SELECTION, "路径不是安全的相对路径")
    }

    private fun withArchive(
        openStream: () -> InputStream,
        isCancelled: () -> Boolean,
        block: (operation: ArchiveOperation, extractedRoot: File) -> SkillInstallResult,
    ): SkillInstallResult {
        val operationDir = createOperationDirectory()
            ?: return SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "无法创建 Skill 安装暂存目录")
            )
        val operation = ArchiveOperation(operationDir)
        return try {
            val archiveFile = File(operationDir, "package.zip")
            operation.archiveSha256 = SkillArchiveSource.materialize(openStream, archiveFile, limits, isCancelled)
            val extractedRoot = File(operationDir, "extracted")
            SkillArchiveExtractor(limits).extract(archiveFile, extractedRoot, isCancelled)
            block(operation, extractedRoot)
        } catch (error: SkillArchiveExtractionException) {
            SkillInstallResult.Failure(error.error)
        } catch (error: SkillInstallException) {
            SkillInstallResult.Failure(error.error)
        } catch (_: SkillRecoveryRequiredException) {
            SkillInstallResult.Failure(
                error = SkillInstallError(
                    SkillInstallErrorCode.COMMIT_FAILED,
                    "检测到未完成的 Skill 恢复，已停止安装",
                ),
                recoveryRequired = true,
            )
        } catch (_: ZipException) {
            SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP 格式无效或内容已损坏")
            )
        } catch (_: IOException) {
            SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "读取或暂存 ZIP 失败")
            )
        } catch (_: SecurityException) {
            SkillInstallResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "没有读取该 ZIP 的权限")
            )
        } finally {
            if (!operation.preserveForRecovery) {
                deleteSkillPathWithoutFollowingLinks(workRoot, operationDir)
            }
        }
    }

    private fun withArchiveInspection(
        openStream: () -> InputStream,
        isCancelled: () -> Boolean,
        block: (extractedRoot: File) -> SkillArchiveInspectionResult,
    ): SkillArchiveInspectionResult {
        val operationDir = createOperationDirectory()
            ?: return SkillArchiveInspectionResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "无法创建 Skill 检查暂存目录")
            )
        return try {
            val archiveFile = File(operationDir, "package.zip")
            SkillArchiveSource.materialize(openStream, archiveFile, limits, isCancelled)
            val extractedRoot = File(operationDir, "extracted")
            SkillArchiveExtractor(limits).extract(archiveFile, extractedRoot, isCancelled)
            block(extractedRoot)
        } catch (error: SkillArchiveExtractionException) {
            SkillArchiveInspectionResult.Failure(error.error)
        } catch (error: SkillInstallException) {
            SkillArchiveInspectionResult.Failure(error.error)
        } catch (_: ZipException) {
            SkillArchiveInspectionResult.Failure(
                SkillInstallError(SkillInstallErrorCode.INVALID_ARCHIVE, "ZIP 格式无效或内容已损坏")
            )
        } catch (_: IOException) {
            SkillArchiveInspectionResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "读取或暂存 ZIP 失败")
            )
        } catch (_: SecurityException) {
            SkillArchiveInspectionResult.Failure(
                SkillInstallError(SkillInstallErrorCode.IO_ERROR, "没有读取该 ZIP 的权限")
            )
        } finally {
            deleteSkillPathWithoutFollowingLinks(workRoot, operationDir)
        }
    }

    private fun createOperationDirectory(): File? {
        val safeWorkRoot = runCatching {
            prepareSkillInstallerWorkRoot(canonicalSkillsRoot)
        }.getOrNull() ?: return null
        if (safeWorkRoot != workRoot) return null
        repeat(4) {
            val candidate = File(safeWorkRoot, "operation-${UUID.randomUUID()}")
            if (candidate.mkdir()) return candidate
        }
        return null
    }

    private fun fail(code: SkillInstallErrorCode, message: String): Nothing =
        throw SkillInstallException(SkillInstallError(code, message))

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) {
            fail(SkillInstallErrorCode.CANCELLED, "Skill 安装已取消")
        }
    }

    private data class PreparedCandidate(
        val id: String,
        val name: String,
        val description: String,
        val relativePath: String,
        val directory: File,
    ) {
        val publicModel: SkillArchiveCandidate
            get() = SkillArchiveCandidate(
                id = id,
                name = name,
                description = description,
                relativePath = relativePath,
            )
    }

    private data class ValidatedSkillMetadata(
        val name: String,
        val description: String,
    )

    private data class ArchiveOperation(
        val directory: File,
        var archiveSha256: String = "",
        var preserveForRecovery: Boolean = false,
    )

    private class SkillInstallException(
        val error: SkillInstallError,
    ) : RuntimeException(error.message)

    private companion object {
        const val SKILL_FILE_NAME = "SKILL.md"
        const val MAX_SKILL_NAME_LENGTH = 64
        const val MAX_SKILL_DESCRIPTION_LENGTH = 1_024
        val SKILL_NAME_REGEX = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")
        val SHA_256_REGEX = Regex("^[a-f0-9]{64}$")
    }
}

internal fun interface SkillDirectoryMover {
    fun move(source: File, target: File)
}

internal object AtomicSkillDirectoryMover : SkillDirectoryMover {
    override fun move(source: File, target: File) {
        moveSkillDirectoryAtomically(source, target)
    }
}
