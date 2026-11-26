package io.github.mangi.eta.agent.skill

/** Parsed SKILL.md data, kept free of Android/Compose dependencies. */
internal data class ParsedSkillFile(
    val frontmatter: Map<String, String>,
    val body: String,
    val revision: String = "",
)
