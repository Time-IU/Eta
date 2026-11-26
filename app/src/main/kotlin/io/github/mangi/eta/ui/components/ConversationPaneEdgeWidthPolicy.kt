package io.github.mangi.eta.ui.components

import io.github.mangi.eta.data.model.CONVERSATION_PANE_EDGE_WIDTH_STEP_DP
import io.github.mangi.eta.data.model.DEFAULT_CONVERSATION_PANE_EDGE_WIDTH_DP
import io.github.mangi.eta.data.model.MIN_CONVERSATION_PANE_EDGE_WIDTH_DP
import kotlin.math.roundToInt

internal object ConversationPaneEdgeWidthPolicy {
    const val SliderIntervals = 6

    fun sliderValueForWidth(valueDp: Int, maxWidthDp: Int): Float {
        val min = MIN_CONVERSATION_PANE_EDGE_WIDTH_DP.toFloat()
        val max = maxOf(min, maxWidthDp.toFloat())
        val width = valueDp.coerceIn(min.toInt(), max.toInt()).toFloat()
        return (((width - min) / (max - min).coerceAtLeast(1f)) * SliderIntervals)
            .coerceIn(0f, SliderIntervals.toFloat())
            .roundToInt()
            .toFloat()
    }

    fun widthForSliderValue(step: Float, maxWidthDp: Int): Int {
        val min = MIN_CONVERSATION_PANE_EDGE_WIDTH_DP.toFloat()
        val max = maxOf(min, maxWidthDp.toFloat())
        val position = step.coerceIn(0f, SliderIntervals.toFloat()) / SliderIntervals
        return (min + (max - min) * position).toInt().coerceIn(min.toInt(), max.toInt())
    }

    fun normalize(valueDp: Float, maxWidthDp: Int): Int =
        (valueDp.toInt() / CONVERSATION_PANE_EDGE_WIDTH_STEP_DP * CONVERSATION_PANE_EDGE_WIDTH_STEP_DP)
            .coerceIn(MIN_CONVERSATION_PANE_EDGE_WIDTH_DP, maxOf(MIN_CONVERSATION_PANE_EDGE_WIDTH_DP, maxWidthDp))

    fun defaultForScreen(screenWidthDp: Int): Int =
        screenWidthDp.coerceAtLeast(MIN_CONVERSATION_PANE_EDGE_WIDTH_DP)

    fun effectiveWidth(valueDp: Int, screenWidthDp: Int): Int =
        if (valueDp == DEFAULT_CONVERSATION_PANE_EDGE_WIDTH_DP) defaultForScreen(screenWidthDp)
        else valueDp.coerceIn(MIN_CONVERSATION_PANE_EDGE_WIDTH_DP, screenWidthDp.coerceAtLeast(MIN_CONVERSATION_PANE_EDGE_WIDTH_DP))

    fun displayValue(valueDp: Int): String = "$valueDp dp"
}
