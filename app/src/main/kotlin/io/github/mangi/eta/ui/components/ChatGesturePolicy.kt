package io.github.mangi.eta.ui.components

import kotlin.math.abs

import io.github.mangi.eta.data.model.DEFAULT_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT

internal object ChatGesturePolicy {
    fun canStart(startX: Float, gestureStartWidth: Float, panelOpen: Boolean, selecting: Boolean,
        workspaceOpen: Boolean, enabled: Boolean): Boolean = enabled && !selecting && !workspaceOpen &&
        (panelOpen || startX >= 0f && startX <= gestureStartWidth)

    fun normalizedSensitivity(sensitivity: Float): Float =
        if (sensitivity.isFinite()) sensitivity.coerceIn(0.75f, 2f) else 1f

    /** Scale drawer travel after native touch-slop arbitration claims horizontal intent. */
    fun scaledDragDelta(delta: Float, sensitivity: Float): Float =
        if (delta.isFinite()) delta * normalizedSensitivity(sensitivity) else 0f

    fun acceptsInitialDelta(delta: Float, panelOpen: Boolean): Boolean = delta.isFinite() &&
        if (panelOpen) delta != 0f else delta > 0f

    fun shouldClaimHorizontal(totalX: Float, totalY: Float, touchSlop: Float): Boolean =
        totalX.isFinite() && totalY.isFinite() && touchSlop.isFinite() && touchSlop > 0f &&
            abs(totalX) >= touchSlop && abs(totalX) > abs(totalY)

    fun settleOpen(offset: Float, width: Float, commitThresholdPercent: Float = DEFAULT_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT): Boolean {
        if (width <= 0f || !offset.isFinite()) return false
        val threshold = if (commitThresholdPercent.isFinite()) {
            commitThresholdPercent.coerceIn(3f, 20f) / 100f
        } else 0.5f
        return offset >= width * threshold
    }

    fun isBeyondCommitThreshold(
        offset: Float,
        width: Float,
        panelWasOpen: Boolean,
        commitThresholdPercent: Float,
    ): Boolean {
        if (width <= 0f || !offset.isFinite()) return false
        val threshold = if (commitThresholdPercent.isFinite()) {
            commitThresholdPercent.coerceIn(3f, 20f) / 100f
        } else 0.115f
        return if (panelWasOpen) offset <= width * (1f - threshold)
        else offset >= width * threshold
    }

    /** One-shot edge crossing used to cue haptics before release commits the transition. */
    fun crossedCommitThreshold(
        previousOffset: Float,
        currentOffset: Float,
        width: Float,
        panelWasOpen: Boolean,
        commitThresholdPercent: Float,
    ): Boolean {
        if (width <= 0f || !previousOffset.isFinite() || !currentOffset.isFinite()) return false
        val threshold = if (commitThresholdPercent.isFinite()) {
            commitThresholdPercent.coerceIn(3f, 20f) / 100f
        } else 0.5f
        val boundary = width * if (panelWasOpen) (1f - threshold) else threshold
        return if (panelWasOpen) previousOffset > boundary && currentOffset <= boundary
        else previousOffset < boundary && currentOffset >= boundary
    }
}
