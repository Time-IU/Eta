package io.github.mangi.eta.agent.skill

/** Shared, platform-independent YAML subset used by runtime parsing and package validation. */
internal object SkillFrontmatterParser {
    private val blockScalar = Regex("[>|][+-]?")

    fun parse(frontmatter: String): Map<String, String> {
        if (frontmatter.isBlank()) return emptyMap()
        val lines = frontmatter.lines()
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < lines.size) {
            val rawLine = lines[index]
            if (rawLine.isBlank()) { index++; continue }
            val keyMatch = Regex("^([A-Za-z0-9_-]+):\\s*(.*)$").find(rawLine)
            if (keyMatch == null) { index++; continue }
            val key = keyMatch.groupValues[1]
            val value = keyMatch.groupValues[2]
            if (blockScalar.matches(value)) {
                val literal = value.startsWith('|')
                val blockLines = mutableListOf<String>()
                index++
                while (index < lines.size && (lines[index].startsWith("  ") || lines[index].isBlank())) {
                    val next = lines[index]
                    blockLines += if (next.isBlank()) "" else next.trim()
                    index++
                }
                result[key] = if (literal) blockLines.joinToString("\n").trim() else fold(blockLines)
                continue
            }
            if (value.isBlank()) {
                val builder = StringBuilder()
                index++
                while (index < lines.size && (lines[index].startsWith("  ") || lines[index].startsWith("\t"))) {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(lines[index].trimEnd())
                    index++
                }
                result[key] = builder.toString().trim()
                continue
            }
            result[key] = unquote(value)
            index++
        }
        return result
    }

    private fun unquote(raw: String): String {
        val value = raw.trim()
        if (value.length < 2) return value
        val quoted = (value.first() == '"' && value.last() == '"') ||
            (value.first() == '\'' && value.last() == '\'')
        return if (quoted) value.substring(1, value.lastIndex) else value
    }

    private fun fold(lines: List<String>): String = buildString {
        var pendingBlankLines = 0
        lines.forEach { line ->
            if (line.isBlank()) pendingBlankLines++ else {
                if (isNotEmpty()) {
                    if (pendingBlankLines == 0) append(' ') else repeat(pendingBlankLines + 1) { append('\n') }
                }
                append(line)
                pendingBlankLines = 0
            }
        }
    }.trim()
}
