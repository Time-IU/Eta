package io.github.mangi.eta.agent.skill

data class SkillPackageLimits(
    val maxArchiveBytes: Long = 32L * 1024L * 1024L,
    val maxExtractedBytes: Long = 128L * 1024L * 1024L,
    val maxSingleFileBytes: Long = 32L * 1024L * 1024L,
    val maxSkillFileBytes: Long = 512L * 1024L,
    val maxEntries: Int = 2_048,
    val maxPathDepth: Int = 16,
)
