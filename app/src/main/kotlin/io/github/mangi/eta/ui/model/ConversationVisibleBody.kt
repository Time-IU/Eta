package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec

/** The body the person actually sees, never skill injection, file framing or hidden paths.
 * This is the shared boundary for outline, search, mentions and every quote entry point.
 * Do not trim: selections and their UTF-16 offsets refer to this exact string.
 */
internal fun AgentChatMessageUi.visibleConversationBody(): String? = when (this) {
    is UserMessageUi -> AgentFileReferencePromptCodec.parse(content).request
    is AgentMessageUi -> content
    is ThinkingMessageUi -> content
    else -> null
}

internal fun AgentChatMessageUi.messageReference(
    sequence: Int,
    selectedText: String? = null,
): MessageReferenceUi? {
    val body = visibleConversationBody()?.takeIf { it.isNotBlank() } ?: return null
    if (selectedText != null && selectedText.isBlank()) return null
    return MessageReferenceUi(
        messageId = id, sequence = sequence, fromUser = this is UserMessageUi,
        preview = (selectedText ?: body).replace(Regex("\\s+"), " ").take(60),
        searchText = body, originalText = body, selectedText = selectedText,
    )
}

/** Shared by panel buttons and message-menu/selection callbacks; unsupported rows stay open. */
internal fun attachConversationReference(
    messages: List<AgentChatMessageUi>, messageId: String,
    messageStartIndex: Int = 0, selectedText: String? = null,
    attach: (MessageReferenceUi) -> Unit,
): Boolean {
    val index = messages.indexOfFirst { it.id == messageId }
    val reference = messages.getOrNull(index)?.messageReference(index + messageStartIndex + 1, selectedText)
        ?: return false
    attach(reference)
    return true
}
