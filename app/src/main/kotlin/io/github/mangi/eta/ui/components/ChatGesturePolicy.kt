package io.github.mangi.eta.ui.components

internal object ChatGesturePolicy {
    fun canStart(startX: Float, edgeWidth: Float, panelOpen: Boolean, selecting: Boolean,
        workspaceOpen: Boolean, enabled: Boolean): Boolean = enabled && !selecting && !workspaceOpen &&
        (panelOpen || startX >= 0f && startX <= edgeWidth)
    fun acceptsInitialDelta(delta: Float, panelOpen: Boolean): Boolean = delta.isFinite() &&
        if (panelOpen) delta != 0f else delta > 0f
    fun settleOpen(offset: Float, width: Float): Boolean = width > 0 && offset.isFinite() && offset >= width * 0.5f
}
