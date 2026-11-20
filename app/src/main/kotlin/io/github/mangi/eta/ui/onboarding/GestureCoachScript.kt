package io.github.mangi.eta.ui.onboarding

/**
 * 手势教学的脚本：一步，按顺序教，做到哪一步记哪一步。
 *
 * 与旧的 `TourPack` 分开：手势不需要锚点、不需要步骤按钮，
 * 只需要「教哪个方向 + 用户有没有真的做过」。
 */
internal object GestureCoachScript {

    data class Step(
        /** 记录用的键：做到就写进 `seenTourPacks`。 */
        val key: String,
        val direction: GestureDirection,
        val title: String,
        val body: String,
    )

    val steps: List<Step> = listOf(
        Step(
            key = OnboardingKeys.SwipeRight,
            direction = GestureDirection.SwipeRight,
            title = "往右滑，拉出会话列表",
            body = "从聊天页左侧边缘向右滑，或点左上角会话按钮。选取文字时不触发滑动开栏。工作区请从菜单打开。",
        ),
    )

    /** 下一个该教的方向；一步都做过返回 null。 */
    fun next(done: Map<String, Int>): Step? = steps.firstOrNull { (done[it.key] ?: 0) < 1 }
}
