package io.github.mangi.eta.core

/** Never default an unresolved foreground user to owner user 0. */
internal fun assistantEntryUserId(resolve: () -> Int): Int = resolve().also {
    require(it >= 0) { "Current assistant user unavailable" }
}

internal inline fun <T> withAssistantCallingIdentity(
    clear: () -> Long,
    restore: (Long) -> Unit,
    action: () -> T,
): T {
    val token = clear()
    return try { action() } finally { restore(token) }
}

internal enum class AssistantEntryFallback { LOCKED_FAILURE, OVERLAY, PERMISSION_GUIDANCE }

internal fun assistantEntryFallback(locked: Boolean, overlayGranted: Boolean): AssistantEntryFallback =
    when {
        locked -> AssistantEntryFallback.LOCKED_FAILURE
        overlayGranted -> AssistantEntryFallback.OVERLAY
        else -> AssistantEntryFallback.PERMISSION_GUIDANCE
    }
