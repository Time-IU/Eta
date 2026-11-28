package io.github.mangi.eta.agent.model

import org.json.JSONObject

/** User-selected, per-model request profiles. Templates only narrow the existing policy. */
internal enum class LocalToolProfile(val id: String, private val toolNames: Set<String>? = null) {
    FULL("full"),
    CHAT("chat", emptySet()),
    CODE("task-code", setOf(
        "get_current_context", "ask_user", "terminal", "terminal_job", "get_logcat",
        "read_file", "stat_file", "list_directory", "grep_files", "glob_files", "search_files",
        "edit_file", "write_file", "web_search", "fetch_url", "conversation_history",
    )),
    PHONE("task-phone", setOf(
        "get_current_context", "ask_user", "search_apps", "launch_app", "open_uri",
        "observe_screen", "release_gui", "present_to_user", "tap_element", "tap_area", "tap",
        "long_press_element", "long_press", "scroll_element", "scroll", "swipe", "type_text",
        "press_key", "wait_for_text", "wait_for_package", "wait",
        "device_status", "network_info", "get_device_state", "set_device_state",
        "set_brightness", "set_volume", "get_flashlight", "set_flashlight", "media_control",
        "set_alarm", "list_alarms", "set_timer", "conversation_history", "list_calendars", "create_calendar_event",
        "search_calendar_events", "create_note", "search_notes", "get_logcat", "read_file", "search_files",
    ));

    val isTaskTemplate: Boolean get() = this == CODE || this == PHONE
    fun allows(name: String): Boolean = toolNames == null || name in toolNames

    // Hosted provider search stays as configured. Arbitrary custom local tool declarations do not
    // replace the template's filtered catalog for this request; saved provider settings are untouched.
    fun requestConfig(config: AgentModelClient.ModelConfig): AgentModelClient.ModelConfig {
        if (!isTaskTemplate) return config
        return config.copy(
            extraBodyJson = config.extraBodyJson.takeIf(String::isNotBlank)?.let { raw ->
                JSONObject(raw).apply { protectedToolKeys.forEach { remove(it) } }.toString()
            }.orEmpty(),
            customBody = config.customBody.filterNot { it.key in protectedToolKeys },
        )
    }

    companion object {
        private val protectedToolKeys = setOf("tools", "tool_choice", "toolConfig", "tool_config", "toolChoice",
            "functions", "function_call", "parallel_tool_calls")
        // Legacy/unknown IDs still mean FULL, not an implicit template selection.
        fun fromId(id: String?): LocalToolProfile = entries.firstOrNull { it.id == id } ?: FULL
    }
}
