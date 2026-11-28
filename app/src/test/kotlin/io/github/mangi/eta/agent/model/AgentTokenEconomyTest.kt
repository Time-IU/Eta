package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.prompt.AgentToolPolicy
import io.github.mangi.eta.agent.prompt.ToolPolicyMode
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.data.model.CustomBody
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentTokenEconomyTest {
    @Test fun oldProfileChoicesArePreserved() {
        assertEquals(LocalToolProfile.CHAT, LocalToolProfile.fromId("chat"))
        assertEquals(LocalToolProfile.FULL, LocalToolProfile.fromId(null))
        assertEquals(LocalToolProfile.FULL, LocalToolProfile.fromId("legacy-profile"))
        assertFalse(LocalToolProfileStore.canSelectTemplate(null, "model"))
        assertFalse(LocalToolProfileStore.canSelectTemplate("provider", " "))
        assertTrue(LocalToolProfileStore.canSelectTemplate("provider", "model"))
        for (profile in LocalToolProfile.entries) assertEquals(profile, LocalToolProfile.fromId(profile.id))
    }

    @Test fun templatesCannotAddToWhitelistOrParentScope() {
        val whitelist = AgentToolPolicy(ToolPolicyMode.ALLOW_LIST, setOf("terminal", "read_file", "set_timer", "mcp_example"))
        val code = directory(LocalToolProfile.CODE, whitelist)
        assertEquals(setOf("terminal", "read_file"), names(code))
        assertEquals(setOf("read_file", "set_timer"), names(directory(LocalToolProfile.PHONE, whitelist)))
        assertEquals(setOf("read_file"), names(directory(LocalToolProfile.CODE, whitelist, setOf("read_file", "set_timer"))))
        assertEquals(emptySet<String>(), names(directory(LocalToolProfile.CHAT, whitelist)))
        assertEquals(whitelist.allowedTools, names(directory(LocalToolProfile.FULL, whitelist)))
        assertTrue(code.toString().contains("result_ref"))
        assertFalse(directory(LocalToolProfile.FULL, whitelist).toString().contains("result_ref"))
    }

    @Test fun templatesRespectDeviceAndTerminalAvailability() {
        val code = names(directory(LocalToolProfile.CODE, AgentToolPolicy(), terminal = false))
        assertFalse("read_file" in code || "terminal" in code || "get_logcat" in code)
        assertTrue("web_search" in code)
        val full = directory(LocalToolProfile.FULL, AgentToolPolicy())
        val phone = directory(LocalToolProfile.PHONE, AgentToolPolicy())
        assertTrue(names(full).containsAll(names(phone)))
        assertTrue(phone.toString().length < full.toString().length)
    }

    @Test fun compilerPreviewKeepsExitCodeAndAnErrorFarFromTheTail() {
        val store = AgentToolResultPreviews()
        val raw = JSONObject().put("ok", false).put("exit_code", 1).put("output_truncated", false)
            .put("stdout", "configure\ncompiler error: unresolved identifier 项目\n" + "incremental task finished.\n".repeat(500))
            .put("stderr", "build failed").toString()
        val compact = store.preview("terminal", AgentModelClient.ToolResult(raw))
        val preview = JSONObject(compact)
        assertTrue(preview.getBoolean("preview_truncated"))
        assertEquals(1, preview.getInt("exit_code"))
        assertFalse(preview.getBoolean("output_truncated"))
        assertTrue(preview.getJSONArray("stdout_diagnostics").toString().contains("unresolved identifier 项目"))
        assertTrue(compact.length * 4 < raw.length)
        assertPages(store, preview.getString("result_ref"), raw)
        println("Synthetic compiler fixture: ${raw.toByteArray().size} → ${compact.toByteArray().size} UTF-8 bytes (not billed tokens)")
    }

    @Test fun refsAreRunLocalPermissionCheckedAndDoNotAccessDisk() {
        val raw = JSONObject().put("lines", JSONArray(List(60) { "甲🔧测试日志".repeat(12) })).toString()
        val store = AgentToolResultPreviews()
        val preview = JSONObject(store.preview("get_logcat", AgentModelClient.ToolResult(raw, sensitive = true)))
        val args = JSONObject().put("path", preview.getString("result_ref"))
        assertEquals("TOOL_RESULT_EXPIRED", JSONObject(AgentToolResultPreviews().read(args, rootAvailable = true) { true }.content).getString("code"))
        assertEquals("TOOL_NOT_ALLOWED_BY_POLICY", JSONObject(store.read(args, rootAvailable = true) { false }.content).getString("code"))
        val page = store.read(args, rootAvailable = true) { it == "get_logcat" }
        assertEquals("TOOL_NOT_ALLOWED_BY_POLICY", JSONObject(store.read(args, rootAvailable = false) { true }.content).getString("code"))
        assertTrue(page.sensitive)
        assertTrue(JSONObject(page.content).getBoolean("ok"))
        assertEquals("INVALID_RESULT_PAGE", JSONObject(store.read(args.put("environment", "linux"), rootAvailable = true) { true }.content).getString("code"))
        assertPages(store, preview.getString("result_ref"), raw)
    }

    @Test fun searchKeepsExactSourcesAndOriginalCoverageFlags() {
        val sources = JSONArray()
        for (index in 1..12) sources.put(JSONObject().put("id", index).put("title", "Source $index")
            .put("url", "https://example.invalid/docs/$index").put("snippet", "重要内容".repeat(100)))
        val raw = JSONObject().put("count", 12).put("truncated", true).put("results", sources).toString()
        val p = JSONObject(AgentToolResultPreviews().preview("web_search", AgentModelClient.ToolResult(raw)))
        assertEquals(12, p.getInt("results_captured_count"))
        assertTrue(p.getBoolean("truncated"))
        assertTrue(p.getBoolean("preview_truncated"))
        assertEquals("https://example.invalid/docs/1", p.getJSONArray("results").getJSONObject(0).getString("url"))
    }

    @Test fun smallImageAndUnlistedResultsAreUnchanged() {
        val store = AgentToolResultPreviews()
        val small = AgentModelClient.ToolResult("""{"ok":false,"exit_code":2,"stderr":"not found"}""")
        assertEquals(small.content, store.preview("terminal", small))
        val data = JSONObject().put("stdout", "x".repeat(20_000)).toString()
        val imageResult = AgentModelClient.ToolResult(data, listOf(AgentModelClient.ModelImage("fixture", "image/png", 7)))
        assertEquals(data, store.preview("terminal", imageResult))
        assertEquals(data, store.preview("mcp_example", AgentModelClient.ToolResult(data)))
    }

    @Test fun projectionDoesNotRewriteStoredHistoryOrSwapCallOccurrences() {
        val old = JSONObject().put("role", "tool").put("tool_call_id", "same-id").put("content", "older result")
        val raw = JSONObject().put("stdout", "progress output\n".repeat(600)).put("identity", "user").toString()
        val current = JSONObject().put("role", "tool").put("tool_call_id", "same-id").put("content", raw)
        val original = JSONArray().put(old).put(current)
        val store = AgentToolResultPreviews()
        store.capture(current, AgentModelClient.ToolCall("same-id", "terminal", "{}"), AgentModelClient.ToolResult(raw), rootAvailable = false)
        val projected = store.project(original)
        assertEquals("older result", projected.getJSONObject(0).getString("content"))
        assertEquals("same-id", projected.getJSONObject(1).getString("tool_call_id"))
        assertTrue(JSONObject(projected.getJSONObject(1).getString("content")).getBoolean("preview_truncated"))
        assertEquals(raw, original.getJSONObject(1).getString("content"))
        assertFalse(original.toString().contains("result_ref"))
    }

    @Test fun customToolOverridesCannotReplaceTemplateDeclarations() {
        val config = AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture-model", systemPrompt = "fixture",
            hostedWebSearchEnabled = true,
            extraBodyJson = """{"tools":[],"tool_choice":"required","temperature":0.3}""",
            customBody = listOf(CustomBody("tools", JsonPrimitive("fixture")), CustomBody("max_tokens", JsonPrimitive(12))),
        )
        val request = LocalToolProfile.CODE.requestConfig(config)
        val extra = JSONObject(request.extraBodyJson)
        assertEquals(setOf("temperature"), extra.keys().asSequence().toSet())
        assertEquals(listOf("max_tokens"), request.customBody.map { it.key })
        assertEquals(config.model, request.model)
        assertEquals(config.hostedWebSearchEnabled, request.hostedWebSearchEnabled)
        assertSame(config, LocalToolProfile.FULL.requestConfig(config))
        assertSame(config, LocalToolProfile.CHAT.requestConfig(config))
    }

    private fun assertPages(store: AgentToolResultPreviews, ref: String, raw: String) {
        val rebuilt = StringBuilder()
        var offset = 0
        do {
            val args = JSONObject().put("path", ref).put("offset_bytes", offset).put("max_bytes", 997)
            val result = store.read(args, rootAvailable = true) { true }
            assertTrue(result.sensitive)
            val page = JSONObject(result.content)
            val part = page.getString("content")
            val next = page.getInt("next_offset_bytes")
            assertEquals(next - offset, part.toByteArray(Charsets.UTF_8).size)
            assertTrue(next > offset && next - offset <= 997)
            assertFalse(part.contains('\ufffd'))
            rebuilt.append(part)
            offset = next
        } while (page.getBoolean("has_more"))
        assertEquals(raw, rebuilt.toString())
    }

    private fun directory(
        profile: LocalToolProfile,
        policy: AgentToolPolicy,
        restricted: Set<String>? = null,
        terminal: Boolean = true,
    ) = AgentToolDirectory.build(
        terminalTools = terminal, browserTools = true, deviceDirectTools = true,
        deviceSensitiveReadTools = false, deviceSensitiveActionTools = false,
        skillGitHubDiscovery = false, skillGitHubInstall = false, memoryTools = false, memoryWritable = false,
        capabilities = AgentToolCapabilities(rootAvailable = false), localWebSearch = true,
        localProfile = profile, policy = policy, restrictedToolNames = restricted,
        additionalTools = JSONArray().put(JSONObject().put("type", "function")
            .put("function", JSONObject().put("name", "mcp_example"))),
    )

    private fun names(tools: JSONArray) = (0 until tools.length()).map {
        tools.getJSONObject(it).getJSONObject("function").getString("name")
    }.toSet()
}
