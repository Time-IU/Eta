package io.github.mangi.eta.ui.components

/** No coroutine, message iteration, or document allocation is needed while hidden/blank. */
internal fun chatSearchWorkEnabled(open: Boolean, query: String): Boolean = open && query.isNotBlank()

internal fun <T> buildActiveChatSearchDocuments(
    open: Boolean,
    query: String,
    messages: List<T>,
    checkCancelled: () -> Unit = {},
    documentFor: (T) -> ChatSearchDocument?,
): List<ChatSearchDocument> {
    if (!chatSearchWorkEnabled(open, query)) return emptyList()
    return messages.mapNotNull { message ->
        checkCancelled()
        documentFor(message)
    }
}
