package io.github.mangi.eta.ui.components

internal object ToolAccessSheetLayoutPolicy {
    fun listHeightDp(itemCount: Int): Int =
        if (itemCount <= 0) 68 else (itemCount * 58).coerceAtMost(360)

    fun heightFraction(itemCount: Int, windowHeightDp: Int): Float {
        val available = windowHeightDp.coerceAtLeast(1)
        val desired = 148 + listHeightDp(itemCount)
        return (desired.toFloat() / available).coerceIn(0.24f, 0.66f)
    }
}
