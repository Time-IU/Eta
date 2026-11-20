package io.github.mangi.eta.agent.runtime

import org.junit.Assert.*
import org.junit.Test

class LiveUpdateColorizationTest {
    @Test fun newPlatformRejectsColorizedAndUsesPlainPayload() {
        val checked = mutableListOf<Boolean>()
        assertFalse(liveUpdateRequiresColorization { checked.add(it); !it })
        assertEquals(listOf(false), checked)
    }
    @Test fun legacyPlatformRequiresColorized() {
        val checked = mutableListOf<Boolean>()
        assertTrue(liveUpdateRequiresColorization { checked.add(it); it })
        assertEquals(listOf(false, true), checked)
    }
    @Test fun bothEligiblePrefersUncolored() {
        assertFalse(liveUpdateRequiresColorization { true })
    }
    @Test fun neitherEligibleDoesNotFabricateColorizationAsSuccess() {
        val checked = mutableListOf<Boolean>()
        assertFalse(liveUpdateRequiresColorization { checked.add(it); false })
        assertEquals(listOf(false, true), checked)
    }
}
