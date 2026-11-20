package io.github.mangi.eta.agent.runtime

internal data class AgentTokenUsage(
    val contextTokens: Int? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val reasoningTokens: Int? = null,
    val cachedTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val requestKind: String? = null,
    val stepCount: Int? = null,
    val llmMs: Long? = null,
    val toolMs: Long? = null,
    val ttftMs: Long? = null,
    val ttftSteps: Int? = null,
    val decodeMs: Long? = null,
    val decodeTokens: Int? = null,
    val requestDurationMs: Long? = null,
    /**
     * 上下文构成估算：本次请求里系统提示词、工具定义（schema）与对话消息各占多少 token。
     *
     * 由请求侧按实际发出去的内容折算（CJK 1 字 1 token、其余 4 字符 1 token），
     * 只用于上下文面板的量级展示；服务端返回的 [contextTokens] 仍是唯一口径的实测值。
     */
    val systemPromptTokens: Int? = null,
    val toolDefinitionTokens: Int? = null,
    val conversationTokens: Int? = null,
    /** 本次 run 累计的首 token 延迟（毫秒）：每个请求「发出 → 第一个增量」之和。 */
    val firstTokenMs: Long? = null,
    /** 本次 run 累计的工具执行耗时（毫秒）。 */
    val toolDurationMs: Long? = null,
    /** 本次 run 里已计入用量的请求数（工具循环每多一轮就多一次）。 */
    val requestCount: Int? = null,
) {
    fun merge(other: AgentTokenUsage): AgentTokenUsage = copy(
        contextTokens = other.contextTokens ?: contextTokens,
        inputTokens = other.inputTokens ?: inputTokens,
        outputTokens = other.outputTokens ?: outputTokens,
        reasoningTokens = other.reasoningTokens ?: reasoningTokens,
        cachedTokens = other.cachedTokens ?: cachedTokens,
        cacheWriteTokens = other.cacheWriteTokens ?: cacheWriteTokens,
        requestKind = other.requestKind ?: requestKind,
        stepCount = other.stepCount ?: stepCount,
        llmMs = other.llmMs ?: llmMs,
        toolMs = other.toolMs ?: toolMs,
        ttftMs = other.ttftMs ?: ttftMs,
        ttftSteps = other.ttftSteps ?: ttftSteps,
        decodeMs = other.decodeMs ?: decodeMs,
        decodeTokens = other.decodeTokens ?: decodeTokens,
        requestDurationMs = other.requestDurationMs ?: requestDurationMs,
        systemPromptTokens = other.systemPromptTokens ?: systemPromptTokens,
        toolDefinitionTokens = other.toolDefinitionTokens ?: toolDefinitionTokens,
        conversationTokens = other.conversationTokens ?: conversationTokens,
        firstTokenMs = other.firstTokenMs ?: firstTokenMs,
        toolDurationMs = other.toolDurationMs ?: toolDurationMs,
        requestCount = other.requestCount ?: requestCount,
    )

    val isEmpty: Boolean
        get() = contextTokens == null &&
            inputTokens == null &&
            outputTokens == null &&
            reasoningTokens == null &&
            cachedTokens == null &&
            cacheWriteTokens == null &&
            requestKind == null &&
            stepCount == null &&
            llmMs == null &&
            toolMs == null &&
            ttftMs == null &&
            ttftSteps == null &&
            decodeMs == null &&
            decodeTokens == null &&
            requestDurationMs == null &&
            systemPromptTokens == null &&
            toolDefinitionTokens == null &&
            conversationTokens == null &&
            firstTokenMs == null &&
            toolDurationMs == null &&
            requestCount == null

    val hasContextComposition: Boolean
        get() = systemPromptTokens != null || toolDefinitionTokens != null || conversationTokens != null

    companion object {
        const val REQUEST_KIND_CHAT = "chat"
        const val REQUEST_KIND_COMPACTION = "compaction"
        const val REQUEST_KIND_SUBAGENT = "subagent"
    }
}
