package io.github.mangi.eta.agent.display.dashboard

/** A conversation-specific user-message submission, not an instruction to execute a tool. */
internal data class ApprovalDecisionRequest(
    val token: String,
    val conversation: String,
    val originatingRun: String,
    val text: String,
)

/** Only return after durable message acceptance, with the exact receiving conversation/run. */
internal data class ApprovalDecisionReceipt(val token: String, val conversation: String, val receivingRun: String) {
    fun matches(request: ApprovalDecisionRequest): Boolean = token == request.token &&
        conversation == request.conversation && conversation.isNotBlank() && receivingRun.isNotBlank()
}

/**
 * Optional UI/repository adapter for an ended run. Must use request.conversation (never selected UI
 * conversation), deduplicate by token, preserve existing drafts, and return a genuine acceptance
 * receipt. Null means no proven acceptance; the pending card and its token remain recoverable.
 * The guard does not invent model configuration/history to start a replacement run itself.
 */
internal fun interface ApprovalDecisionSubmitter {
    suspend fun submit(request: ApprovalDecisionRequest): ApprovalDecisionReceipt?
}
