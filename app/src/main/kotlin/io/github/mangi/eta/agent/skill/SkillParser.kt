package io.github.mangi.eta.agent.skill

import java.io.File

/**
 * SKILL.md 解析器——从 YAML frontmatter + Markdown body 中提取结构化信息。
 *
 * 支持 `>` / `|` 多行块、缩进子块，以及普通的 `key: value` 行。
 * 纯字符串处理，不依赖外部 YAML 库。
 */
internal object SkillParser {

    /**
     * 读取并解析 [skillFile]，返回 frontmatter map + body string。
     * 文件不存在或不是文件时返回 null。
     */
    fun parseSkillFile(skillFile: File): ParsedSkillFile? {
        if (!skillFile.exists() || !skillFile.isFile) return null
        val bytes = skillFile.readBytes()
        val raw = bytes.toString(Charsets.UTF_8)
        val revision = skillRevision(bytes)
        if (!raw.startsWith("---")) {
            return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim(), revision = revision)
        }
        val markerIndex = raw.indexOf("\n---", startIndex = 3)
        if (markerIndex <= 0) {
            return ParsedSkillFile(frontmatter = emptyMap(), body = raw.trim(), revision = revision)
        }
        val frontmatterText = raw.substring(3, markerIndex).trim('\n', '\r')
        val body = raw.substring(markerIndex + 4).trim()
        return ParsedSkillFile(
            frontmatter = parseSimpleFrontmatter(frontmatterText),
            body = body,
            revision = revision,
        )
    }

    /**
     * 简单 YAML frontmatter 解析。
     *
     * 支持：
     * - `key: value` 单行
     * - `key: >` 折叠多行块
     * - `key: |` 字面多行块
     * - `key:` 后跟缩进子块
     */
    fun parseSimpleFrontmatter(frontmatter: String): Map<String, String> =
        SkillFrontmatterParser.parse(frontmatter)

    /**
     * 解析缩进子块为 key-value map（用于 metadata 字段）。
     */
    fun parseIndentedBlock(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.lines().mapNotNull { line ->
            val match = Regex("^\\s*([A-Za-z0-9_.-]+):\\s*(.*)$").find(line) ?: return@mapNotNull null
            match.groupValues[1] to match.groupValues[2].trim().trim('"')
        }.toMap()
    }

    /**
     * 从目录名和 frontmatter name 生成规范化的 skill id。
     */
    fun sanitizeSkillId(directoryName: String, frontmatterName: String?): String {
        val candidate = frontmatterName?.trim().takeUnless { it.isNullOrBlank() } ?: directoryName
        return candidate.lowercase()
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .ifBlank { directoryName.lowercase() }
    }

    /**
     * 规范化查找字符串——用于 id/name/path 匹配。
     */
    fun normalizeSkillLookup(value: String): String =
        value.trim()
            .lowercase()
            .replace('\\', '/')
            .removeSuffix("/skill.md")
            .removeSuffix("/")
            .replace(Regex("\\s+"), "")
            .replace("-", "")
            .replace("_", "")


}

internal fun skillRevision(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
