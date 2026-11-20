package io.github.mangi.eta.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf

internal class ChatSelectionLock {
    private val tokens = mutableStateMapOf<Any, Boolean>()
    val active: Boolean get() = tokens.isNotEmpty()
    fun acquire(token: Any) { tokens[token] = true }
    fun release(token: Any) { tokens.remove(token) }
}
internal val LocalChatSelectionLock = compositionLocalOf<ChatSelectionLock?> { null }
