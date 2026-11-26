package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.skill.SkillIndexEntry
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.subagent.SubagentProfile
import io.github.mangi.eta.agent.subagent.SubagentProfileCatalog
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class UserExtensionItem(
    val id: String,
    val name: String,
    val description: String,
    val enabled: Boolean = true,
    val conflict: Boolean = false,
    val archivePath: String = id,
)

internal data class UserExtensionInventory(
    val skills: List<UserExtensionItem> = emptyList(),
    val subagents: List<UserExtensionItem> = emptyList(),
)

internal data class UserExtensionBackupResult(
    val installedSkills: Int,
    val installedSubagents: Int,
    val skippedConflicts: List<String>,
)

/** Separate, selectable archive for user-created Skills and Subagent definitions only. */
internal object EtaUserExtensionsBackup {
    private const val FORMAT = "eta-user-extensions"
    private const val VERSION = 1
    private const val MAX_ARCHIVE_BYTES = 384L * 1024L * 1024L
    private const val MAX_EXPANDED_BYTES = 256L * 1024L * 1024L
    private const val MAX_FILE_BYTES = 32L * 1024L * 1024L
    private const val MAX_ENTRIES = 4096
    private const val MAX_PROFILE_BYTES = 64 * 1024L
    private const val MAX_PROFILE_TOTAL_BYTES = 16L * 1024L * 1024L
    private const val MAX_PROFILE_COUNT = 512
    private val skillIdRegex = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    fun available(context: Context): UserExtensionInventory {
        val app = context.applicationContext
        val skills = SkillRuntime.createIndexService(app).listSkillsForManagement(forceRefresh = true)
            .filter { it.installed && it.source == "user" }
            .map { UserExtensionItem(it.id, it.name, it.description, it.enabled, archivePath = "skills/${it.id}") }
        val subagents = SubagentProfileCatalog.listCustom()
            .filter { !it.builtin && it.name !in SubagentProfile.BUILTIN_NAMES }
            .map { UserExtensionItem(it.name, it.name, it.summary) }
        return UserExtensionInventory(skills, subagents)
    }

    fun export(
        context: Context,
        output: OutputStream,
        selectedSkillIds: Set<String>,
        selectedSubagentNames: Set<String>,
    ) {
        require(selectedSkillIds.isNotEmpty() || selectedSubagentNames.isNotEmpty()) { "至少选择一个 Skills 或 Subagent" }
        val app = context.applicationContext
        val skillsRoot = SkillRuntime.skillsRoot(app).canonicalFile
        val entries = SkillRuntime.createIndexService(app).listSkillsForManagement(forceRefresh = true)
            .filter { it.installed && it.source == "user" && it.id in selectedSkillIds }
        require(entries.map { it.id }.toSet() == selectedSkillIds) { "所选用户 Skill 不再全部可用，请刷新后重试" }
        val customProfiles = SubagentProfileCatalog.listCustom()
            .filter { !it.builtin && it.name !in SubagentProfile.BUILTIN_NAMES && it.name in selectedSubagentNames }
        require(customProfiles.map { it.name }.toSet() == selectedSubagentNames) { "所选自定义 Subagent 不再全部可用，请刷新后重试" }

        val manifest = JSONObject().put("format", FORMAT).put("version", VERSION)
            .put("skills", JSONArray().apply {
                entries.sortedBy { it.id }.forEach { put(JSONObject().put("id", it.id).put("enabled", it.enabled)) }
            })
            .put("subagents", JSONArray().apply {
                customProfiles.sortedBy { it.name }.forEach { put(JSONObject().put("name", it.name)) }
            })
        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            var total = 0L
            var count = 1
            entries.sortedBy { it.id }.forEach { entry ->
                val root = File(entry.rootPath).canonicalFile
                require(root.toPath().startsWith(skillsRoot.toPath()) && root != skillsRoot) { "Skill 路径越出技能目录" }
                require(root.isDirectory && !Files.isSymbolicLink(root.toPath())) { "Skill 目录不可安全读取：${entry.id}" }
                root.walkTopDown().forEach { file ->
                    require(!Files.isSymbolicLink(file.toPath())) { "Skill 中包含符号链接，拒绝备份：${entry.id}" }
                    if (!file.isFile) return@forEach
                    val canonical = file.canonicalFile.toPath()
                    require(canonical.startsWith(root.toPath())) { "Skill 文件路径越界：${entry.id}" }
                    val relative = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
                    require(safeRelativePath(relative)) { "Skill 含非法路径" }
                    val size = file.length()
                    require(size <= MAX_FILE_BYTES) { "Skill 单个文件超过备份上限：${file.name}" }
                    total += size
                    count++
                    require(total <= MAX_EXPANDED_BYTES && count <= MAX_ENTRIES) { "Skills/Subagent 总备份内容超过上限" }
                    zip.putNextEntry(ZipEntry("skills/${entry.id}/$relative"))
                    FileInputStream(file).use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            customProfiles.sortedBy { it.name }.forEach { profile ->
                val source = profile.filePath?.let(::File) ?: File(SubagentProfileCatalog.defaultAgentsDir(), "${profile.name}.md")
                require(source.isFile && !Files.isSymbolicLink(source.toPath()) && source.length() in 1..MAX_PROFILE_BYTES) {
                    "Subagent 定义无法安全读取：${profile.name}"
                }
                val canonicalRoot = SubagentProfileCatalog.defaultAgentsDir().canonicalFile.toPath()
                val canonicalFile = source.canonicalFile.toPath()
                require(canonicalFile.startsWith(canonicalRoot)) { "Subagent 路径越出 agents 目录" }
                val bytes = source.readBytes()
                total += bytes.size
                count++
                require(total <= MAX_EXPANDED_BYTES && count <= MAX_ENTRIES) { "Skills/Subagent 总备份内容超过上限" }
                zip.putNextEntry(ZipEntry("agents/${profile.name}.md"))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.finish()
        }
    }

    suspend fun inspect(context: Context, input: InputStream): UserExtensionInventory = withContext(Dispatchers.IO) {
        stageAndInspect(context, input).use { staged -> staged.inventory }
    }

    suspend fun importSelected(
        context: Context,
        input: InputStream,
        selectedSkillIds: Set<String>,
        selectedSubagentNames: Set<String>,
    ): UserExtensionBackupResult = withContext(Dispatchers.IO) {
        require(selectedSkillIds.isNotEmpty() || selectedSubagentNames.isNotEmpty()) { "至少选择一个项目恢复" }
        stageAndInspect(context, input).use { staged ->
            val app = context.applicationContext
            val conflicts = mutableListOf<String>()
            val createdSkillIds = mutableListOf<String>()
            val createdProfileNames = mutableListOf<String>()
            var installedProfiles = 0
            try {
                val availableSkills = staged.inventory.skills.filter { it.id in selectedSkillIds }
                require(availableSkills.map { it.id }.toSet() == selectedSkillIds) { "备份中找不到所选 Skill" }
                if (availableSkills.isNotEmpty()) {
                    val index = SkillRuntime.createIndexService(app)
                    val existingIds = index.listSkillsForManagement(forceRefresh = true)
                        .filter { it.installed }
                        .mapTo(mutableSetOf()) { it.id }
                    val freshSkills = availableSkills.filter { it.id !in existingIds }
                    availableSkills.filter { it.id in existingIds }.forEach { conflicts += "Skill: ${it.name}" }
                    if (freshSkills.isNotEmpty()) {
                        val result = SkillRuntime.createPackageInstaller(app).installRepositoryZip(
                            openStream = { FileInputStream(staged.archive) },
                            selectedPaths = freshSkills.map { it.archivePath },
                            replaceUserSkills = false,
                            enabledById = freshSkills.associate { it.id to it.enabled },
                        )
                        when (result) {
                            is SkillInstallResult.Success -> createdSkillIds += result.installed.map { it.id }
                            is SkillInstallResult.Conflict -> result.conflicts.forEach { conflicts += "Skill: ${it.name}" }
                            is SkillInstallResult.Failure -> error("Skill 恢复失败：${result.error.message}")
                        }
                    }
                }

                val profiles = staged.profileFiles.filterKeys { it in selectedSubagentNames }
                require(profiles.keys == selectedSubagentNames) { "备份中找不到所选 Subagent" }
                val agentsDir = SubagentProfileCatalog.defaultAgentsDir()
                val existingNames = SubagentProfileCatalog.listCustom().mapTo(mutableSetOf()) { it.name }
                profiles.toSortedMap().forEach { (name, bytes) ->
                    if (name in SubagentProfile.BUILTIN_NAMES || name in existingNames) {
                        conflicts += "Subagent: $name"
                        return@forEach
                    }
                    val stagedFile = File(staged.directory, "profile-$name.md").apply { writeBytes(bytes) }
                    val parsed = SubagentProfileCatalog.parseProfileFile(stagedFile)
                        ?: error("Subagent 文件无效：$name")
                    require(parsed.name == name && !parsed.builtin) { "Subagent 名称与备份清单不匹配：$name" }
                    SubagentProfileCatalog.saveCustomProfile(
                        name = parsed.name,
                        description = parsed.summary,
                        prompt = parsed.roleInstruction,
                        whenToUse = parsed.whenToUse,
                        tools = parsed.explicitTools.orEmpty().toList(),
                        disallowedTools = parsed.disallowedTools.toList(),
                        overrideBuiltin = false,
                        agentsDir = agentsDir,
                    )
                    createdProfileNames += name
                    installedProfiles++
                }
                UserExtensionBackupResult(createdSkillIds.size, installedProfiles, conflicts)
            } catch (failure: Throwable) {
                // Treat a selected Skills+Subagents restore as one logical operation. Roll back
                // only items created by this invocation; existing conflicts were never touched.
                createdProfileNames.asReversed().forEach { name ->
                    runCatching { SubagentProfileCatalog.deleteCustomProfile(name) }
                        .onFailure(failure::addSuppressed)
                }
                if (createdSkillIds.isNotEmpty()) {
                    runCatching {
                        val index = SkillRuntime.createIndexService(app)
                        createdSkillIds.asReversed().forEach { id ->
                            check(index.deleteSkill(id)) { "新恢复的 Skill 回滚未确认：$id" }
                        }
                    }.onFailure(failure::addSuppressed)
                }
                throw failure
            }
        }
    }

    private fun stageAndInspect(context: Context, input: InputStream): StagedArchive {
        val directory = File(context.applicationContext.cacheDir, "eta-user-extension-import-${System.nanoTime()}")
        require(directory.mkdir()) { "无法创建扩展备份暂存目录" }
        val archive = File(directory, "input.zip")
        try {
            var archiveBytes = 0L
            FileOutputStream(archive).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    archiveBytes += read
                    require(archiveBytes <= MAX_ARCHIVE_BYTES) { "扩展备份 ZIP 超过大小上限" }
                    output.write(buffer, 0, read)
                }
            }
            val manifest = JSONObject()
            val profileFiles = linkedMapOf<String, ByteArray>()
            val archiveSkillIds = linkedSetOf<String>()
            val entryNames = mutableSetOf<String>()
            var expanded = 0L
            var profileBytesTotal = 0L
            var count = 0
            ZipInputStream(FileInputStream(archive)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val path = entry.name.trimEnd('/')
                    require(safeRelativePath(path)) { "ZIP 中含非法路径" }
                    require(entryNames.add(path)) { "ZIP 中存在重复文件路径" }
                    count++
                    require(count <= MAX_ENTRIES) { "ZIP 文件条目过多" }
                    if (entry.isDirectory) continue
                    val bytes = zip.readBounded(MAX_FILE_BYTES) { size ->
                        expanded += size
                        require(expanded <= MAX_EXPANDED_BYTES) { "ZIP 解压内容超过总上限" }
                    }
                    when {
                        path == "manifest.json" -> {
                            require(manifest.length() == 0) { "备份 manifest 重复" }
                            val parsed = JSONObject(bytes.toString(Charsets.UTF_8))
                            require(parsed.optString("format") == FORMAT && parsed.optInt("version") == VERSION) {
                                "不支持的自定义扩展备份格式"
                            }
                            manifest.put("format", parsed.getString("format"))
                            manifest.put("version", parsed.getInt("version"))
                            manifest.put("skills", parsed.optJSONArray("skills") ?: JSONArray())
                            manifest.put("subagents", parsed.optJSONArray("subagents") ?: JSONArray())
                        }
                        path.startsWith("agents/") && path.endsWith(".md") -> {
                            val fileName = path.substringAfterLast('/').removeSuffix(".md")
                            require(SubagentProfileCatalog.isValidName(fileName) && bytes.size in 1..MAX_PROFILE_BYTES) { "Subagent 文件名或大小无效" }
                            require(profileFiles.size < MAX_PROFILE_COUNT) { "Subagent 文件数量过多" }
                            profileBytesTotal += bytes.size
                            require(profileBytesTotal <= MAX_PROFILE_TOTAL_BYTES) { "Subagent 备份内容超过总上限" }
                            require(profileFiles.put(fileName, bytes) == null) { "Subagent 名称重复" }
                        }
                        path.startsWith("skills/") -> {
                            val parts = path.split('/')
                            require(parts.size >= 2 && skillIdRegex.matches(parts[1])) { "ZIP 中 Skill 路径无效" }
                            archiveSkillIds += parts[1]
                        }
                        else -> error("扩展备份含未知条目：$path")
                    }
                    zip.closeEntry()
                }
            }
            require(manifest.length() > 0) { "ZIP 缺少有效 manifest" }
            val skillStates = linkedMapOf<String, Boolean>()
            val manifestSkills = manifest.getJSONArray("skills")
            for (index in 0 until manifestSkills.length()) {
                val item = manifestSkills.getJSONObject(index)
                val id = item.getString("id")
                require(skillIdRegex.matches(id) && skillStates.put(id, item.optBoolean("enabled", true)) == null) {
                    "Skill manifest 中存在重复或非法 ID"
                }
            }
            val manifestProfiles = (0 until manifest.getJSONArray("subagents").length()).map { index ->
                manifest.getJSONArray("subagents").getJSONObject(index).getString("name")
            }
            require(manifestProfiles.all(SubagentProfileCatalog::isValidName) && manifestProfiles.distinct().size == manifestProfiles.size) {
                "Subagent manifest 中存在重复或非法名称"
            }
            require(profileFiles.keys == manifestProfiles.toSet()) { "Subagent 文件与 manifest 不一致" }
            val candidates = if (skillStates.isEmpty()) emptyList() else {
                val installerResult = SkillRuntime.createPackageInstaller(context.applicationContext)
                    .inspectRepositoryZip(openStream = { FileInputStream(archive) })
                when (installerResult) {
                    is io.github.mangi.eta.agent.skill.SkillArchiveInspectionResult.Success -> installerResult.candidates
                    is io.github.mangi.eta.agent.skill.SkillArchiveInspectionResult.Failure -> error(installerResult.error.message)
                }
            }
            require(archiveSkillIds == skillStates.keys) { "Skill 路径与 manifest 不一致" }
            require(candidates.map { it.id }.toSet() == skillStates.keys) { "Skill 文件与 manifest 不一致" }
            val all = SkillRuntime.createIndexService(context.applicationContext).listSkillsForManagement(forceRefresh = true)
            val builtinIds = all.filter { it.source == "builtin" }.mapTo(mutableSetOf()) { it.id }
            require(skillStates.keys.none { it in builtinIds }) { "备份包含内置 Skill，拒绝覆盖" }
            val conflictSkillIds = all.filter { it.installed }.mapTo(mutableSetOf()) { it.id }
            val customNames = SubagentProfileCatalog.listCustom().mapTo(mutableSetOf()) { it.name }
            val skills = candidates.map { item ->
                UserExtensionItem(item.id, item.name, item.description, skillStates.getValue(item.id), item.id in conflictSkillIds, item.relativePath)
            }
            val subagents = profileFiles.map { (name, bytes) ->
                val file = File(directory, "validate-$name.md").apply { writeBytes(bytes) }
                val profile = SubagentProfileCatalog.parseProfileFile(file)
                    ?: error("Subagent 文件无效：$name")
                require(profile.name == name && !profile.builtin) { "Subagent 文件名与 frontmatter 不一致：$name" }
                UserExtensionItem(name, name, profile.summary, conflict = name in customNames || name in SubagentProfile.BUILTIN_NAMES)
            }
            return StagedArchive(directory, archive, UserExtensionInventory(skills, subagents), profileFiles)
        } catch (failure: Throwable) {
            directory.deleteRecursively()
            throw failure
        }
    }

    private data class StagedArchive(
        val directory: File,
        val archive: File,
        val inventory: UserExtensionInventory,
        val profileFiles: Map<String, ByteArray>,
    ) : AutoCloseable {
        override fun close() { directory.deleteRecursively() }
    }

    private fun safeRelativePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith('/') || path.startsWith('\\') || '\\' in path) return false
        val parts = path.split('/')
        return parts.all { it.isNotBlank() && it != "." && it != ".." } &&
            !parts.first().contains(':')
    }

    private fun ZipInputStream.readBounded(limit: Long, onBytes: (Long) -> Unit): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var size = 0L
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            size += count
            require(size <= limit) { "ZIP 中单个文件超过大小上限" }
            onBytes(count.toLong())
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
