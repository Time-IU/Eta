package io.github.mangi.eta.agent.tool

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UnifiedCanvasHookCapabilitiesTest {
    @Test fun hookOnlyCanvasPassesRequirementButRootToolsStayForbidden() {
        val hook = AgentToolCapabilities(rootAvailable = false, lsposedAvailable = true, virtualScreenEnabled = true)
        assertNull(hook.unavailableCode("virtual_screen"))
        assertNull(hook.unavailableCode("lingscreen_open_app"))
        assertNull(hook.unavailableCode("tap"))
        assertEquals("ROOT_REQUIRED", hook.unavailableCode("wifi_credentials"))
        assertFalse(AgentToolRequirements.rootDenied("virtual_screen", JSONObject(), false, true))
        assertTrue(AgentToolRequirements.rootDenied("wifi_credentials", JSONObject(), false, true, true))
    }
    @Test fun frameworkConnectionDoesNotGrantAccessibilityOrRootFallback() {
        val hook = AgentToolCapabilities(rootAvailable = false, lsposedAvailable = true,
            virtualScreenEnabled = true, accessibilityAvailable = false, accessibilityRecoveryAvailable = false)
        assertEquals("ACCESSIBILITY_UNAVAILABLE", hook.unavailableCode("tap"))
        assertFalse(AgentToolRequirements.rootDenied("press_key", JSONObject().put("button", "PASTE"), false, true, true))
        assertTrue(AgentToolRequirements.rootDenied("press_key", JSONObject().put("button", "PASTE"), false, true, false))
    }
    @Test fun schemaKeepsHookCanvasWithoutProjectingRootIntoUnrelatedTools() {
        fun tool(name: String) = JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", name).put("description", "test").put("parameters", JSONObject().put("type", "object").put("properties", JSONObject())))
        val tools = JSONArray().put(tool("virtual_screen")).put(tool("lingscreen_open_app")).put(tool("wifi_credentials"))
        val visible = AgentToolRequirements.project(tools, false, true)
        assertEquals(2, visible.length())
        assertEquals("virtual_screen", visible.getJSONObject(0).getJSONObject("function").getString("name"))
        assertEquals(3, tools.length())
        assertEquals(0, AgentToolRequirements.project(tools, false, false).length())
    }
}
