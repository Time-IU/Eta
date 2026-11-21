package io.github.mangi.eta.ui.model

import org.junit.Assert.*
import org.junit.Test

class ToolScopeSelectorTest {
    private fun group(vararg names: String) = ToolGroupUi("test", "Test", names.map { ToolItemUi(it, it, it) })

    @Test fun identicalResultsDoNotOfferMeaninglessToggle() {
        val groups = listOf(group("observe_screen", "terminal"))
        assertFalse(toolScopeDiffers(groups, groups))
    }
    @Test fun allSupportedOnRootColorOsDoNotOfferDuplicateTabs() {
        val groups = listOf(group("observe_screen", "wifi_credentials", "search_coloros_memories"))
        assertFalse(toolScopeDiffers(projectToolGroups(groups, false, true, true),
            projectToolGroups(groups, true, true, true)))
    }
    @Test fun rootlessDeviceStillCanDiscoverRootCapabilities() {
        val groups = listOf(group("observe_screen", "wifi_credentials"))
        assertTrue(toolScopeDiffers(projectToolGroups(groups, false, false, false),
            projectToolGroups(groups, true, false, false)))
    }
    @Test fun otherRomStillCanDiscoverOemOnlyCapabilities() {
        val groups = listOf(group("observe_screen", "search_coloros_memories"))
        assertTrue(toolScopeDiffers(projectToolGroups(groups, false, true, false),
            projectToolGroups(groups, true, true, false)))
    }
    @Test fun labelOrGroupingDifferencesDoNotCreateSecondTab() {
        assertFalse(toolScopeDiffers(listOf(group("observe_screen")),
            listOf(group("observe_screen").copy(id="different", title="Renamed"))))
    }
    @Test fun emptyResultsDoNotOfferDuplicateTabs() = assertFalse(toolScopeDiffers(emptyList(), emptyList()))
}
