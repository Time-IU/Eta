package io.github.mangi.eta.ui.screens.prompts
import org.junit.Assert.*
import org.junit.Test
class ToolGroupingExternalTest {
    @Test fun permissionGroupingAcceptsDynamicMcpWithoutClaimingRootPermissions() {
        val external = "mcp_d3837e4e_mt_apk_build_d90a76bd"
        val tools = listOf("observe_screen", "get_logcat", external, "future_external_tool")
        val result = groupingOf(ToolSelectorState(mode = io.github.mangi.eta.agent.prompt.ToolPolicyMode.ALLOW_LIST, allowed = emptySet(), grouping = "权限"), tools)
        assertEquals(tools.toSet(), result.grouped.values.flatten().toSet())
        assertEquals(listOf(external, "future_external_tool"), result.grouped["EXTERNAL"])
        assertFalse(result.grouped["NONE"].orEmpty().contains(external))
    }
    @Test fun emptyNameDoesNotCrashAlphabetGrouping() {
        val result = groupingOf(ToolSelectorState(mode = io.github.mangi.eta.agent.prompt.ToolPolicyMode.ALLOW_LIST, allowed = emptySet(), grouping = "字母"), listOf("", "observe_screen"))
        assertEquals(2, result.grouped.values.sumOf { it.size })
    }
}
