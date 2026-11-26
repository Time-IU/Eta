package io.github.mangi.eta.agent.skill

import java.text.Normalizer
import java.util.Locale

/** Platform-independent validation shared by archive handling and local JVM tests. */
internal object SkillPackagePolicy {
    private const val MAX_PATH_SEGMENT_LENGTH = 255
    private val windowsDrivePrefix = Regex("^[A-Za-z]:")

    fun validateRelativePath(raw: String): List<String>? {
        if (
            raw.isBlank() || raw.startsWith('/') || raw.startsWith('\\') ||
            windowsDrivePrefix.containsMatchIn(raw) || raw.contains('\\') || raw.contains('\u0000') ||
            raw.any { it.isISOControl() }
        ) return null
        val segments = raw.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." || it.length > MAX_PATH_SEGMENT_LENGTH }) {
            return null
        }
        return segments
    }

    fun normalizeSelectionPath(raw: String): String? {
        val value = raw.trim().removeSuffix("/")
        if (value == ".") return value
        return validateRelativePath(value)?.joinToString("/")
    }

    fun collisionKey(path: String): String = Normalizer
        .normalize(path, Normalizer.Form.NFC)
        .lowercase(Locale.ROOT)

    fun strictFrontmatter(raw: String): String? {
        val firstLineEnd = raw.indexOf('\n')
        if (firstLineEnd < 0 || raw.substring(0, firstLineEnd).trimEnd('\r') != "---") return null
        var lineStart = firstLineEnd + 1
        while (lineStart <= raw.length) {
            val lineEnd = raw.indexOf('\n', lineStart).let { if (it < 0) raw.length else it }
            if (raw.substring(lineStart, lineEnd).trimEnd('\r') == "---") {
                return raw.substring(firstLineEnd + 1, lineStart).trimEnd('\r', '\n')
            }
            if (lineEnd == raw.length) break
            lineStart = lineEnd + 1
        }
        return null
    }

    fun validateSkillMetadata(raw: String, maxNameLength: Int = 64, maxDescriptionLength: Int = 1_024):
        SkillPackageMetadata? {
        val frontmatter = strictFrontmatter(raw) ?: return null
        val parsed = SkillFrontmatterParser.parse(frontmatter)
        val name = parsed["name"]?.trim().orEmpty()
        val description = parsed["description"]?.trim().orEmpty()
        if (name.length !in 1..maxNameLength || !Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$").matches(name)) return null
        if (description.isBlank() || description.length > maxDescriptionLength) return null
        return SkillPackageMetadata(name, description)
    }
}

internal data class SkillPackageMetadata(val name: String, val description: String)
