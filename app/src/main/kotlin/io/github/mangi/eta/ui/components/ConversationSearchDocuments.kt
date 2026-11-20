package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.ConversationNavEntry
import io.github.mangi.eta.ui.model.ConversationNavRole
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.visibleConversationBody

internal fun conversationSearchDocuments(
    messages: List<AgentChatMessageUi>, checkCancelled: () -> Unit = {},
): List<ChatSearchDocument> {
    var turn = 0
    return messages.mapNotNull { message ->
        checkCancelled()
        if (message is UserMessageUi) turn++
        message.visibleConversationBody()?.let { ChatSearchDocument(message.id, it, turn, message is UserMessageUi) }
    }
}

internal data class ConversationSearchRow(
    val originalHitIndex: Int, val hit: ChatSearchHit, val entry: ConversationNavEntry,
)

/** No second query/metadata filter: roles and body come from the same indexed document.
 * Preserve original ordinal even for a defensive missing-document case in an invalid snapshot.
 */
internal fun conversationSearchRows(documents: List<ChatSearchDocument>, hits: List<ChatSearchHit>): List<ConversationSearchRow> {
    val byId = documents.associateBy { it.messageId }
    return hits.mapIndexedNotNull { index, hit ->
        val doc = byId[hit.messageId] ?: return@mapIndexedNotNull null
        val preview = chatSearchPreview(doc.body, hit)
        ConversationSearchRow(index, hit, ConversationNavEntry(
            messageId = doc.messageId, turn = doc.turn,
            role = if (doc.fromUser) ConversationNavRole.User else ConversationNavRole.Assistant,
            text = preview.before + preview.match + preview.after,
        ))
    }
}
