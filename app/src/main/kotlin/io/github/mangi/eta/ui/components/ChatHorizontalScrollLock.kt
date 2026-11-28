package io.github.mangi.eta.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf

/** A child horizontal scroller temporarily blocks the parent history-drawer gesture. */
internal class ChatHorizontalScrollLock {
    private val tokens = mutableStateMapOf<Any, Boolean>()
    val active: Boolean get() = tokens.isNotEmpty()
    fun acquire(token: Any) { tokens[token] = true }
    fun release(token: Any) { tokens.remove(token) }
}

internal val LocalChatHorizontalScrollLock = compositionLocalOf<ChatHorizontalScrollLock?> { null }
