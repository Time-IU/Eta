package io.github.mangi.eta.ui.components.custom

/** Explicit menu actions and a stationary body double tap request selection; long-press opens the menu. */
internal data class CustomMessageInteractionState(
    val menuOpen: Boolean = false,
    val selecting: Boolean = false,
) {
    fun openMenu(enabled: Boolean) = if (enabled) copy(menuOpen = true) else this
    fun dismissMenu() = copy(menuOpen = false)
    fun beginSelection(enabled: Boolean, hasText: Boolean) =
        if (enabled && hasText) copy(menuOpen = false, selecting = true) else this
}
