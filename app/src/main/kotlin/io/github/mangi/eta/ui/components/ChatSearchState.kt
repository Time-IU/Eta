package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class ChatSearchSnapshot(
    val query: String,
    val documents: List<ChatSearchDocument>,
    val hits: List<ChatSearchHit>,
    val sourceMessages: List<AgentChatMessageUi>? = null,
)

/** Owned by the conversation's keyed composition, never shared/persisted across conversations. */
@Stable
internal class ChatSearchState {
    var open by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
        private set
    private var selected by mutableStateOf<ChatSearchAnchor?>(null)
    private var selectedIndex by mutableIntStateOf(-1)
    var navigationVersion by mutableIntStateOf(0)
        private set
    var snapshot by mutableStateOf<ChatSearchSnapshot?>(null)
        private set
    private val current get() = snapshot?.takeIf { open && it.query == query }
    val hits get() = current?.hits.orEmpty()
    val index get() = if (current != null) selectedIndex else -1
    val hit get() = hits.getOrNull(index)
    val searching get() = open && query.isNotBlank() && current == null
    val preview: ChatSearchPreview?
        get() {
            val active = hit ?: return null
            val document = current?.documents?.firstOrNull { it.messageId == active.messageId } ?: return null
            return chatSearchPreview(document.body, active)
        }

    fun openSearch() { open = true }
    fun changeQuery(value: String) {
        if (value == query) return
        query = value
        selected = null
        selectedIndex = -1
    }
    fun update(result: ChatSearchSnapshot) {
        // A worker can finish just before Compose cancels its old LaunchedEffect.
        if (!open || result.query != query) return
        selectedIndex = resolveChatSearchIndex(result.hits, selected)
        selected = result.hits.getOrNull(selectedIndex)?.anchor
        snapshot = result
    }
    fun move(direction: Int) {
        selectedIndex = nextChatSearchIndex(index, hits.size, direction)
        selected = hits.getOrNull(selectedIndex)?.anchor
        locate()
    }
    fun selectHit(index: Int) {
        selectedIndex = index.takeIf { it in hits.indices } ?: return
        selected = hits[selectedIndex].anchor
        locate()
    }
    fun locate() { navigationVersion++ }
    fun close() {
        open = false
        query = ""
        selected = null
        selectedIndex = -1
        snapshot = null
    }
}

@Composable
internal fun rememberChatSearch(messages: List<AgentChatMessageUi>): ChatSearchState {
    val state = remember { ChatSearchState() }
    // No worker/effect exists while hidden, even when streamed messages change every frame.
    if (chatSearchWorkEnabled(state.open, state.query)) LaunchedEffect(messages, state.query) {
        val query = state.query
        val open = state.open
        // Do not scan on the UI thread or debounce the stream indefinitely. Cancellation drops
        // outdated computations; the last same-query snapshot stays usable while new text arrives.
        val result = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val documents = conversationSearchDocuments(messages) { context.ensureActive() }
            ChatSearchSnapshot(query, documents, findChatSearchHits(documents, query) { context.ensureActive() }, messages)
        }
        state.update(result)
    }
    return state
}

internal val LocalConversationSearch = androidx.compose.runtime.compositionLocalOf<ChatSearchState?> { null }
internal val LocalConversationSearchPaging = androidx.compose.runtime.compositionLocalOf<ChatSearchHistoryPagingState?> { null }
