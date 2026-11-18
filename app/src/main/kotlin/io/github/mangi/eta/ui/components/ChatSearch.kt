package io.github.mangi.eta.ui.components

/** Offsets are UTF-16 offsets in the original body, never in rendered/visible list items. */
internal data class ChatSearchDocument(val messageId: String, val body: String)
internal data class ChatSearchAnchor(val messageId: String, val start: Int)
internal data class ChatSearchHit(val messageId: String, val start: Int, val end: Int) {
    val anchor: ChatSearchAnchor get() = ChatSearchAnchor(messageId, start)
}

/** Literal, case-insensitive search, including overlapping occurrences. No regex or locale folding. */
internal fun findChatSearchHits(
    documents: List<ChatSearchDocument>,
    query: String,
    checkCancelled: () -> Unit = {},
): List<ChatSearchHit> {
    if (query.isBlank()) return emptyList()
    return buildList {
        documents.forEach { document ->
            checkCancelled()
            var from = 0
            while (from <= document.body.length - query.length) {
                val start = document.body.indexOf(query, startIndex = from, ignoreCase = true)
                if (start < 0) break
                add(ChatSearchHit(document.messageId, start, start + query.length))
                // Advance by one code point, preserving overlapping hits without splitting emoji.
                from = start + Character.charCount(Character.codePointAt(document.body, start))
                if (size % 256 == 0) checkCancelled()
            }
        }
    }
}

/** Preserve the actual occurrence through stream appends and history prepends, not its old ordinal. */
internal fun resolveChatSearchIndex(hits: List<ChatSearchHit>, selected: ChatSearchAnchor?): Int {
    if (hits.isEmpty()) return -1
    if (selected == null) return 0
    val exact = hits.indexOfFirst { it.messageId == selected.messageId && it.start == selected.start }
    if (exact >= 0) return exact
    return hits.indexOfFirst { it.messageId == selected.messageId && it.start >= selected.start }
        .takeIf { it >= 0 } ?: 0
}

internal fun nextChatSearchIndex(current: Int, count: Int, direction: Int): Int =
    if (count <= 0) -1 else Math.floorMod(current.coerceAtLeast(0) + direction, count)

internal data class ChatSearchPreview(val before: String, val match: String, val after: String)

/** A bounded, single-line context is always visible even for a hit deep in a huge Markdown block. */
internal fun chatSearchPreview(body: String, hit: ChatSearchHit): ChatSearchPreview {
    val start = hit.start.coerceIn(0, body.length)
    val end = hit.end.coerceIn(start, body.length)
    fun safeStart(offset: Int): Int = if (
        offset in 1 until body.length && Character.isLowSurrogate(body[offset]) &&
        Character.isHighSurrogate(body[offset - 1])
    ) offset - 1 else offset
    fun compact(text: String): String = text.replace(Regex("\\s+"), " ")
    val contextStart = safeStart((start - 64).coerceAtLeast(0))
    val visibleEnd = safeStart((start + 160).coerceAtMost(end))
    val contextEnd = safeStart((end + 100).coerceAtMost(body.length))
    return ChatSearchPreview(
        before = (if (contextStart > 0) "…" else "") + compact(body.substring(contextStart, start)),
        match = compact(body.substring(start, visibleEnd)) + if (visibleEnd < end) "…" else "",
        after = compact(body.substring(end, contextEnd)) + if (contextEnd < body.length) "…" else "",
    )
}
