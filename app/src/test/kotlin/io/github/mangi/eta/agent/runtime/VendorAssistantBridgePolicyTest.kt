package io.github.mangi.eta.agent.runtime

import org.junit.Assert.*
import org.junit.Test

class VendorAssistantBridgePolicyTest {
    @Test fun rejectsSpoofedUninstalledUnsignedAndCrossUserCallers() {
        assertNull(VendorAssistantBridgePolicy.source("arbitrary.app", true, true, true, true))
        assertNull(VendorAssistantBridgePolicy.source("com.heytap.speechassist", false, true, true, true))
        assertNull(VendorAssistantBridgePolicy.source("com.heytap.speechassist", true, false, true, true))
        assertNull(VendorAssistantBridgePolicy.source("com.heytap.speechassist", true, true, false, true))
        assertNull(VendorAssistantBridgePolicy.source("com.heytap.speechassist", true, true, true, false))
    }

    @Test fun onlySystemVendorsWithVerifiedUidAndTrustedSignerAreAccepted() {
        assertEquals("breeno", VendorAssistantBridgePolicy.source("com.heytap.speechassist", true, true, true, true))
        assertEquals("xiaoai", VendorAssistantBridgePolicy.source("com.miui.voiceassist", true, true, true, true))
    }

    @Test fun opaqueRunIdsCannotCrossUidSourceOrFirstPartyBoundary() {
        val run = VendorAssistantBridgePolicy.runId("breeno", 10123, "task")
        assertEquals(run, VendorAssistantBridgePolicy.runId("breeno", 10123, run))
        assertTrue(VendorAssistantBridgePolicy.owns("breeno", 10123, run))
        assertFalse(VendorAssistantBridgePolicy.owns("xiaoai", 10123, run))
        assertFalse(VendorAssistantBridgePolicy.owns("breeno", 10124, run))
        assertFalse(VendorAssistantBridgePolicy.owns("breeno", 10123, "task"))
        assertFalse(VendorAssistantBridgePolicy.owns("breeno", 10123, "vendor:breeno:101230:task"))
    }

    @Test fun legacyOutboxCanBeRecoveredButNeverAnotherUidNamespace() {
        assertTrue(VendorAssistantBridgePolicy.ownsResultId("breeno", 10123, "legacy-task"))
        assertFalse(VendorAssistantBridgePolicy.ownsResultId("breeno", 10123, ""))
        assertFalse(VendorAssistantBridgePolicy.ownsResultId("breeno", 10123, "vendor:breeno:10124:task"))
        assertFalse(VendorAssistantBridgePolicy.ownsResultId("breeno", 10123, "vendor:xiaoai:10123:task"))
    }

    @Test fun maximumLengthRunIdRoundTripsForCancellation() {
        val id = VendorAssistantBridgePolicy.runId("breeno", 10123, "x".repeat(1024))
        assertEquals(id, VendorAssistantBridgePolicy.runId("breeno", 10123, id))
    }

    @Test fun blankAndOversizedIdentifiersAreRejectedBeforeDispatch() {
        assertThrows(IllegalArgumentException::class.java) { VendorAssistantBridgePolicy.runId("breeno", 10123, "") }
        assertThrows(IllegalArgumentException::class.java) { VendorAssistantBridgePolicy.runId("breeno", 10123, "x".repeat(1025)) }
    }
}
