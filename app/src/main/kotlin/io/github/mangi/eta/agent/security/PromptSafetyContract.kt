package io.github.mangi.eta.agent.security

internal object PromptSafetyContract {
    const val VERSION = 1
    val anchors = listOf(
        "不得编造未取得的数据",
        "不包含可供 GUI 工具使用的 observation_id",
        "缺少会影响执行结果的关键信息时，再简短询问",
        "只读取证不得修改源数据；仅在实际工具和权限允许的范围内读取。",
        "不同画布的截图、节点和坐标不能混用；操作前必须取得目标画布的新观察。",
    )
    val systemBlock: String get() = "工具安全合同 v$VERSION：" + anchors.joinToString("；")
    fun missing(text: String): List<String> = anchors.filterNot(text::contains)
    enum class Result { CURRENT_VALID, CURRENT_MISSING, LEGACY_REQUIRES_NEW_SNAPSHOT, NON_AGENT_REQUEST }
    fun assess(text: String, version: Int, applicable: Boolean): Result = when {
        !applicable -> Result.NON_AGENT_REQUEST
        version != VERSION -> Result.LEGACY_REQUIRES_NEW_SNAPSHOT
        missing(text).isNotEmpty() -> Result.CURRENT_MISSING
        else -> Result.CURRENT_VALID
    }
}
