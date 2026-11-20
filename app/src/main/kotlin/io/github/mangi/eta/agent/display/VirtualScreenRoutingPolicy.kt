package io.github.mangi.eta.agent.display

/** Per-run routing authority. Never consult a process-global display or another owner's session. */
internal class VirtualScreenRoutingPolicy(initiallyRouted: Boolean = false) {
    enum class Target { MAIN, CANVAS, VIRTUAL_SCREEN }
    @Volatile var target = if (initiallyRouted) Target.VIRTUAL_SCREEN else Target.MAIN
        private set
    @Volatile var displayId: Int = 0
        private set
    @Volatile var revision: Long = 0
        private set
    @Volatile var usesPrimary: Boolean = false
        private set

    // Disabled virtual permission must fail on that route, never redirect coordinates to main.
    fun shouldRoute(name: String, enabled: Boolean): Boolean =
        name in uiTools && name != LINGSCREEN_ENTRY && target == Target.VIRTUAL_SCREEN

    @Synchronized fun selectMain() { select(Target.MAIN, 0); usesPrimary = false }
    @Synchronized fun selectCanvas(id: Int) {
        require(id > 0)
        select(Target.CANVAS, id); usesPrimary = false
    }
    @Synchronized fun selectVirtual() { select(Target.VIRTUAL_SCREEN, -1); usesPrimary = false }
    @Synchronized fun approvePrimary() { select(Target.MAIN, 0); usesPrimary = true }
    @Synchronized fun invalidate() { revision++ }
    private fun select(next: Target, id: Int) { target = next; displayId = id; revision++ }

    companion object {
        /** 唯一能开启灵屏路由的工具：其余 UI 工具只在它之后跟随。 */
        const val LINGSCREEN_ENTRY = "lingscreen_open_app"

        val fallbackErrors = setOf(
            "ROOT_REQUIRED", "DEVICE_UNSUPPORTED", "ROOT_DISPLAY_UNAVAILABLE", "VIRTUAL_SCREEN_FAILED",
            "CANVAS_BACKEND_UNAVAILABLE", "HOOK_UNAVAILABLE", "HOOK_DISPLAY_CREATE_FAILED", "DISPLAY_ISOLATION_UNAVAILABLE",
            "HOOK_INPUT_PERMISSION_DENIED", "HOOK_TOUCH_REJECTED", "HOOK_HANDOFF_UNCONFIRMED", "HOOK_TASK_QUERY_UNAVAILABLE", "HOOK_CONTINUOUS_TOUCH_UNAVAILABLE",
            "DISPLAY_LIFECYCLE_UNAVAILABLE", "IME_ISOLATION_UNAVAILABLE", "DISPLAY_CREATE_FAILED",
            "DISPLAY_GONE", "NO_VIRTUAL_SCREEN", "ROOT_DISPLAY_DISCONNECTED", "DISPLAY_FRAME_PENDING",
            "APP_ALREADY_RUNNING", "DISPLAY_APP_UNSUPPORTED", "DISPLAY_LAUNCH_REJECTED", "DISPLAY_LAUNCH_MISMATCH",
            "VIRTUAL_ACTION_UNSUPPORTED", "ACCESSIBILITY_UNAVAILABLE",
        )
        val allowedBeforePrimaryObservation = setOf("launch_app", "open_uri", "observe_screen", "wait", "wait_for_text", "wait_for_package")
        val uiTools = setOf(
            // launch_app chooses main/legacy canvas explicitly; follow-up UI uses this run target.
            "lingscreen_open_app",
            "open_uri",
            "observe_screen",
            "tap",
            "tap_area",
            "tap_smart",
            "tap_element",
            "long_press",
            "long_press_element",
            "swipe",
            "scroll",
            "scroll_element",
            "type_text",
            "input_text",
            "replace_text",
            "clear_text",
            "set_clipboard",
            "get_clipboard",
            "paste_text",
            "press_key",
            "wait",
            "wait_for_text",
            "wait_for_package",
            "open_system_panel",
        )
        // browser_use 操作的是 App 内自有的离屏 WebView，不经灵屏窗口转发，
        // 因此不应算作 UI 工具，也不能因为灵屏开启而被判为不可用。
        // 闹钟与计时器走 AlarmClock + EXTRA_SKIP_UI，不产生界面，灵屏会话里照常可用；
        // 只有真正要把系统面板拉到屏幕上的 open_system_panel 仍然禁用。
        val unavailableTools = setOf("open_system_panel")
        val coordinateTools = setOf(
            "observe_screen",
            "tap",
            "tap_area",
            "tap_smart",
            "long_press",
            "swipe",
            "scroll",
            "press_key",
            "wait_for_package",
            "type_text", "input_text", "replace_text", "clear_text", "paste_text", "set_clipboard", "get_clipboard",
        )
        val nodeTools = setOf("tap_element", "long_press_element", "scroll_element", "wait_for_text")
    }
}
