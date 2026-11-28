package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.prompt.AgentToolPolicy
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import org.json.JSONArray

/**
 * 工具目录的唯一构建入口。
 *
 * 请求侧（[AgentModelClient]）与「当前生效提示词」预览共用这一份，
 * 避免两处各写一份 flag 导致"预览 19 个、实际发 34 个"这类对不上。
 */
internal object AgentToolDirectory {
    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean,
        deviceSensitiveReadTools: Boolean,
        deviceSensitiveActionTools: Boolean,
        skillGitHubDiscovery: Boolean,
        skillGitHubInstall: Boolean,
        memoryTools: Boolean,
        memoryWritable: Boolean,
        capabilities: AgentToolCapabilities,
        localWebSearch: Boolean,
        localProfile: LocalToolProfile,
        policy: AgentToolPolicy,
        additionalTools: JSONArray? = null,
        restrictedToolNames: Set<String>? = null,
        rewriteReply: Boolean = false,
    ): JSONArray {
        if (rewriteReply) return JSONArray()
        // 纯聊天档不声明任何工具，请求里也不会带 tools 字段。
        if (localProfile == LocalToolProfile.CHAT) return JSONArray()
        val tools = AgentToolCatalog.build(
            terminalTools = terminalTools,
            browserTools = browserTools,
            deviceDirectTools = deviceDirectTools,
            deviceSensitiveReadTools = deviceSensitiveReadTools,
            deviceSensitiveActionTools = deviceSensitiveActionTools,
            skillGitHubDiscovery = skillGitHubDiscovery,
            skillGitHubInstall = skillGitHubInstall,
            memoryTools = memoryTools,
            memoryWritable = memoryWritable,
            capabilities = capabilities,
            localWebSearch = localWebSearch,
        )
        if (additionalTools != null) {
            for (index in 0 until additionalTools.length()) {
                tools.put(additionalTools.opt(index))
            }
        }
        val policyTools = policy.filterTools(tools)
        if (!localProfile.isTaskTemplate && restrictedToolNames == null) return policyTools
        return JSONArray().also { filtered ->
            for (index in 0 until policyTools.length()) {
                val schema = policyTools.getJSONObject(index)
                val function = schema.optJSONObject("function") ?: continue
                val name = function.optString("name")
                if (!localProfile.allows(name) || (restrictedToolNames != null && name !in restrictedToolNames)) continue
                if (localProfile.isTaskTemplate && name == "read_file") {
                    // Clone only the decorated schema: never mutate a reusable catalog entry.
                    val copy = org.json.JSONObject(schema.toString())
                    copy.getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
                        .getJSONObject("path").apply {
                            put("description", optString("description") + " 或本轮返回的 result_ref，用于按页读取已捕获结果。")
                        }
                    filtered.put(copy)
                } else filtered.put(schema)
            }
        }
    }
}
