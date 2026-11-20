package io.github.mangi.eta.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

@Composable
internal fun conversationLookupTitle(): String {
    val locale = LocalConfiguration.current.locales[0]
    return when {
        locale.language != "zh" -> "Find in conversation"
        locale.country in setOf("TW", "HK", "MO") -> "會話尋找"
        else -> "会话查找"
    }
}

@Composable
internal fun conversationReferenceLabel(): String {
    val locale = LocalConfiguration.current.locales[0]
    return if (locale.language == "zh") "引用" else "Quote"
}
