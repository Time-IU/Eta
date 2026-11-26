package io.github.mangi.eta.ui.components

import io.github.mangi.eta.data.model.DEFAULT_CONVERSATION_PANE_EDGE_WIDTH_DP
import io.github.mangi.eta.data.model.MIN_CONVERSATION_PANE_EDGE_WIDTH_DP
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationPaneEdgeWidthPolicyTest {
    @Test fun defaultFollowsHalfTheScreenAndBoundsTrackFullScreenWidth() {
        assertEquals(0, DEFAULT_CONVERSATION_PANE_EDGE_WIDTH_DP)
        assertEquals(360, ConversationPaneEdgeWidthPolicy.defaultForScreen(360))
        assertEquals(393, ConversationPaneEdgeWidthPolicy.defaultForScreen(393))
        assertEquals(MIN_CONVERSATION_PANE_EDGE_WIDTH_DP, ConversationPaneEdgeWidthPolicy.normalize(5f, 400))
        assertEquals(400, ConversationPaneEdgeWidthPolicy.normalize(500f, 400))
        assertEquals(360, ConversationPaneEdgeWidthPolicy.effectiveWidth(0, 360))
        assertEquals(6, ConversationPaneEdgeWidthPolicy.SliderIntervals)
        assertEquals(0f, ConversationPaneEdgeWidthPolicy.sliderValueForWidth(16, 400))
        assertEquals(3f, ConversationPaneEdgeWidthPolicy.sliderValueForWidth(208, 400))
        assertEquals(400, ConversationPaneEdgeWidthPolicy.widthForSliderValue(6f, 400))
        assertEquals(208, ConversationPaneEdgeWidthPolicy.widthForSliderValue(3f, 400))
    }
}
