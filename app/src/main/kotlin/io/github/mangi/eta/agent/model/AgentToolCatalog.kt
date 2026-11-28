package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.config.Prefs
import org.json.JSONArray

/** 声明模型可见的工具及其 JSON Schema；不包含任何执行逻辑。 */
internal object AgentToolCatalog {
    fun build(
        terminalTools: Boolean,
        browserTools: Boolean,
        deviceDirectTools: Boolean = true,
        deviceSensitiveReadTools: Boolean = false,
        deviceSensitiveActionTools: Boolean = false,
        skillGitHubDiscovery: Boolean = false,
        skillGitHubInstall: Boolean = false,
        memoryTools: Boolean = false,
        memoryWritable: Boolean = true,
        capabilities: AgentToolCapabilities = AgentToolCapabilities(rootAvailable = true),
        localWebSearch: Boolean = true,
        disabledTools: Set<String> = emptySet(),
    ): JSONArray =
        capabilities.project(JSONArray().also { tools ->
            // AgentToolDirectory applies profile and policy after capability projection.
            AgentContextAppToolCatalog.appendTo(tools, includeOwnedScreen = deviceDirectTools)
            // 主动提问：模型自己需要用户拍板时用，和系统拦截共用同一张卡。
            AgentAskToolCatalog.appendTo(tools)
            AgentGestureToolCatalog.appendTo(tools)
            AgentTextSystemToolCatalog.appendTo(tools)
            AgentDeviceToolCatalog.appendTo(
                tools,
                directTools = deviceDirectTools,
                sensitiveReadTools = deviceSensitiveReadTools,
                sensitiveActionTools = deviceSensitiveActionTools,
            )
            if (browserTools) {
                AgentWebToolCatalog.appendTo(tools, includeSearch = localWebSearch)
                AgentBrowserToolCatalog.appendTo(tools)
            }
            AgentSkillToolCatalog.appendTo(
                tools,
                githubDiscovery = skillGitHubDiscovery,
                githubInstall = skillGitHubInstall,
                authoring = memoryWritable,
            )
            if (memoryTools) AgentMemoryToolCatalog.appendTo(tools, writable = memoryWritable)
            if (memoryWritable) AgentTaskToolCatalog.appendTo(tools)
            if (deviceDirectTools) AgentVirtualScreenToolCatalog.appendTo(tools)
            if (terminalTools) {
                AgentFileVisionToolCatalog.appendTo(tools)
                AgentTerminalToolCatalog.appendTo(tools)
                AgentFileToolCatalog.appendTo(tools)
            }
        }).let { projected ->
            if (disabledTools.isEmpty()) {
                projected
            } else {
                JSONArray().also { filtered ->
                    for (index in 0 until projected.length()) {
                        val tool = projected.getJSONObject(index)
                        val name = tool.getJSONObject("function").getString("name")
                        if (Prefs.isToolEnabledInSet(name, disabledTools)) {
                            filtered.put(tool)
                        }
                    }
                }
            }
        }
}
