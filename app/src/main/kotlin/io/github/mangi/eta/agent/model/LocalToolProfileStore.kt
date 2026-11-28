package io.github.mangi.eta.agent.model

import io.github.mangi.eta.config.Prefs

/**
 * 「工具档」档位的持久化：同步读写，供请求组装时就地取值。
 * 按「服务商 + 模型」各记一份，切模型自动带出该模型上次用的档位。
 */
internal object LocalToolProfileStore {
    /** 旧版全局键：只在拿不到模型身份时兜底，保证升级后不丢已选档位。 */
    private const val LEGACY_KEY = "agent_local_tool_profile"
    private const val LEGACY_PREFIX = "agent_local_tool_profile@"
    private const val KEY_PREFIX = "agent_local_tool_profile_v2@"

    fun current(providerId: String? = null, modelId: String? = null): LocalToolProfile {
        val prefs = runCatching { Prefs.localAgentPreferences() }.getOrNull() ?: return LocalToolProfile.FULL
        val scopedKey = scopedKey(providerId, modelId)
        val scoped = scopedKey?.let { prefs.getString(it, null) }
        if (scoped != null) return LocalToolProfile.fromId(scoped)
            .takeUnless { it.isTaskTemplate && !canSelectTemplate(providerId, modelId) } ?: LocalToolProfile.FULL
        val legacyScoped = legacyScopedKey(providerId, modelId)?.let { prefs.getString(it, null) }
        return if (legacyScoped != null) fromLegacyId(legacyScoped) else fromLegacyId(prefs.getString(LEGACY_KEY, null))
    }

    fun save(profile: LocalToolProfile, providerId: String? = null, modelId: String? = null) {
        if (profile.isTaskTemplate && !canSelectTemplate(providerId, modelId)) return
        runCatching {
            val editor = Prefs.localAgentPreferences()?.edit() ?: return
            editor.putString(scopedKey(providerId, modelId) ?: LEGACY_KEY, profile.id).apply()
        }
    }

    /** 模型串为空时不写分模型键，退回旧全局键，避免把不同模型混成同一份。 */
    private fun scopedKey(providerId: String?, modelId: String?): String? {
        val model = modelId?.trim().orEmpty()
        if (model.isEmpty()) return null
        return KEY_PREFIX + encode(providerId.orEmpty()) + "|" + encode(model)
    }

    private fun legacyScopedKey(providerId: String?, modelId: String?): String? {
        val model = modelId?.trim().orEmpty()
        if (model.isEmpty()) return null
        return LEGACY_PREFIX + sanitize(providerId.orEmpty()) + "|" + sanitize(model)
    }

    fun canSelectTemplate(providerId: String?, modelId: String?): Boolean =
        !providerId.isNullOrBlank() && !modelId.isNullOrBlank()

    private fun encode(raw: String): String = buildString(raw.length * 4) {
        raw.forEach { unit -> append(unit.code.toString(16).padStart(4, '0')) }
    }

    private fun sanitize(raw: String): String = raw.map { ch ->
        if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') ch else '_'
    }.joinToString("")

    private fun fromLegacyId(id: String?): LocalToolProfile = when (id) {
        "chat" -> LocalToolProfile.CHAT
        else -> LocalToolProfile.FULL
    }
}
