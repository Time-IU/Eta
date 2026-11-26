package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.mangi.eta.ui.components.ToolAccessSheetLayoutPolicy

class ToolAccessUiTest {
    @Test fun sheetListHeightGrowsWithItemsAndIsCapped() {
        assertEquals(68, ToolAccessSheetLayoutPolicy.listHeightDp(0))
        assertEquals(58, ToolAccessSheetLayoutPolicy.listHeightDp(1))
        assertEquals(116, ToolAccessSheetLayoutPolicy.listHeightDp(2))
        assertEquals(360, ToolAccessSheetLayoutPolicy.listHeightDp(100))
        assertEquals(0.24f, ToolAccessSheetLayoutPolicy.heightFraction(0, 1000), 0.001f)
        assertEquals(0.66f, ToolAccessSheetLayoutPolicy.heightFraction(100, 600), 0.001f)
    }

    @Test fun mcpAndSkillEntriesAreSeparatedForIndependentInputMenus() {
        val mcp = ToolToggleItem("server", "Server", null, true, ToolToggleKind.Mcp)
        val skill = ToolToggleItem("skill", "Skill", null, false, ToolToggleKind.Skill)
        val state = ToolAccessUiState(listOf(mcp, skill))
        assertEquals(listOf(mcp), state.itemsFor(ToolToggleKind.Mcp))
        assertEquals(listOf(skill), state.itemsFor(ToolToggleKind.Skill))
    }
}
