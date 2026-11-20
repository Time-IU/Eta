package io.github.mangi.eta.ui.screens.tasks

import io.github.mangi.eta.data.model.VirtualScreenIdleTimeout

/** UI-only mapping. Never normalize a saved value or change runtime idle semantics. */
internal class CanvasIdleTimeoutSelection(private val currentMinutes: Int) {
    // Preserve an unfamiliar saved value honestly instead of showing the first preset.
    // Only canonical runtime options may be newly selected and persisted.
    val minutes: List<Int> = VirtualScreenIdleTimeout.options.let { options ->
        if (currentMinutes in options) options else options + currentMinutes
    }
    val selectedIndex: Int = minutes.indexOf(currentMinutes)

    fun changedMinutes(index: Int, enabled: Boolean): Int? {
        if (!enabled) return null
        return minutes.getOrNull(index)?.takeIf {
            it in VirtualScreenIdleTimeout.options && it != currentMinutes
        }
    }
}
