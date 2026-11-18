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
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
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
    LaunchedEffect(messages, state.query, state.open) {
        if (!state.open) return@LaunchedEffect
        val query = state.query
        // Do not scan on the UI thread or debounce the stream indefinitely. Cancellation drops
        // outdated computations; the last same-query snapshot stays usable while new text arrives.
        val result = withContext(Dispatchers.Default) {
            val context = currentCoroutineContext()
            val documents = if (query.isBlank()) emptyList() else messages.mapNotNull { message ->
                context.ensureActive()
                val body = when (message) {
                    is UserMessageUi -> message.content
                    is AgentMessageUi -> message.content
                    is ThinkingMessageUi -> message.content
                    else -> null // Tool metadata/system status are not message bodies.
                }
                body?.let { ChatSearchDocument(message.id, it) }
            }
            ChatSearchSnapshot(query, documents, findChatSearchHits(documents, query) { context.ensureActive() }, messages)
        }
        state.update(result)
    }
    return state
}
