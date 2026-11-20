package io.github.mangi.eta.data.update

import java.net.URI

/** User-service destinations only; this does not replace upstream attribution or licenses. */
internal object PersonalDistribution {
    const val REPOSITORY_URL = "https://github.com/Time-IU/Eta"
    const val FEEDBACK_URL = "$REPOSITORY_URL/issues"
    const val RELEASES_URL = "$REPOSITORY_URL/releases"
    const val RELEASES_API = "https://api.github.com/repos/Time-IU/Eta/releases?per_page=100"

    fun isReleaseUrl(tag: String, url: String): Boolean = runCatching {
        val uri = URI(url)
        tag.matches(Regex("v?[0-9]+(?:\\.[0-9]+){1,2}-custom\\.[0-9]+(?:\\.[0-9]+)*(?:-(?:dev|alpha|beta|rc)(?:\\.[0-9]+)?)?")) &&
            uri.scheme == "https" && uri.rawAuthority == "github.com" &&
            uri.rawPath == "/Time-IU/Eta/releases/tag/$tag" &&
            uri.rawQuery == null && uri.rawFragment == null
    }.getOrDefault(false)

    fun versionSummary(version: String, language: String, traditional: Boolean = false): String {
        val base = Regex("^v?([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)").find(version)?.groupValues?.get(1) ?: "?"
        val testing = "debug" in version || "integration" in version ||
            Regex("-(dev|alpha|beta|rc)(?:[.+-]|$)").containsMatchIn(version)
        return when (language) {
            "zh" -> "$base ${if (traditional) "定製版" else "定制版"}" +
                if (testing) if (traditional) " · 測試" else " · 测试" else ""
            else -> "$base Custom" + if (testing) " · Test" else ""
        }
    }

    fun noCompatibleRelease(language: String, traditional: Boolean = false): String = when (language) {
        "zh" -> if (traditional) "暫無適用於此定製版本的更新" else "暂无适用于此定制版本的更新"
        else -> "No compatible update for this custom build"
    }

    fun buildDetails(version: String, code: Long, head: String, treeState: String): String =
        "versionName: $version\nversionCode: $code\nHEAD: $head\nSource tree: $treeState\nRepository: $REPOSITORY_URL"
}

/** Fail closed for unversioned integration/local builds: no approved release migration exists. */
internal object PersonalVersionPolicy {
    private data class Version(val base: List<Long>, val custom: List<Long>?, val stage: String?, val stageNumber: Long)
    private val pattern = Regex("^v?([0-9]+(?:\\.[0-9]+){1,2})(?:-custom\\.([0-9]+(?:\\.[0-9]+)*)(?:-(dev|alpha|beta|rc)(?:\\.([0-9]+))?)?)?$")

    private fun parse(raw: String): Version? {
        // Build metadata never establishes release lineage. Legacy integration metadata is explicitly local.
        if (raw.contains("integration", ignoreCase = true)) return null
        val normalized = raw.substringBefore('+').removeSuffix("-debug")
        val m = pattern.matchEntire(normalized) ?: return null
        fun numbers(value: String): List<Long>? {
            val parts = value.split('.').map { it.toLongOrNull() ?: return null }
            return parts
        }
        return Version(numbers(m.groupValues[1]) ?: return null,
            m.groupValues[2].takeIf { it.isNotEmpty() }?.let { numbers(it) ?: return null },
            m.groupValues[3].takeIf { it.isNotEmpty() },
            m.groupValues[4].ifEmpty { "0" }.toLongOrNull() ?: return null)
    }

    private fun compareNumbers(a: List<Long>, b: List<Long>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val difference = (a.getOrElse(i) { 0L }).compareTo(b.getOrElse(i) { 0L })
            if (difference != 0) return difference
        }
        return 0
    }

    /** Generic same-lineage comparator retained for the existing version tests. */
    fun isNewer(latest: String, current: String): Boolean {
        val a = parse(latest) ?: return false
        val b = parse(current) ?: return false
        if ((a.custom == null) != (b.custom == null)) return false
        val base = compareNumbers(a.base, b.base)
        if (base != 0) return base > 0
        if (a.custom != null && b.custom != null) {
            val custom = compareNumbers(a.custom, b.custom)
            if (custom != 0) return custom > 0
        }
        val stages = listOf("dev", "alpha", "beta", "rc", null)
        val stage = stages.indexOf(a.stage).compareTo(stages.indexOf(b.stage))
        return if (stage != 0) stage > 0 else a.stageNumber > b.stageNumber
    }

    fun isCompatibleUpdate(latest: String, current: String): Boolean =
        parse(latest)?.custom != null && parse(current)?.custom != null && isNewer(latest, current)
}
