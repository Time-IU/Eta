package io.github.mangi.eta.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantEntryRoutingTest {
    @Test fun currentUserIsNotAssumedToBeOwner() {
        assertEquals(10, assistantEntryUserId { 10 })
        assertTrue(runCatching { assistantEntryUserId { -10000 } }.isFailure)
        assertTrue(runCatching { assistantEntryUserId { error("unavailable") } }.isFailure)
    }

    @Test fun identityIsRestoredAfterSuccessAndFailure() {
        for (fail in listOf(false, true)) {
            val events = mutableListOf<String>()
            val result = runCatching {
                withAssistantCallingIdentity(
                    clear = { events += "clear"; 42L },
                    restore = { events += "restore:$it" },
                ) { events += "request"; check(!fail); "requested" }
            }
            assertEquals(listOf("clear", "request", "restore:42"), events)
            assertEquals(fail, result.isFailure)
        }
    }

    @Test fun unavailableSessionHasVisibleOrPermissionFallbackWithoutUnlockBypass() {
        assertEquals(AssistantEntryFallback.OVERLAY, assistantEntryFallback(false, true))
        assertEquals(AssistantEntryFallback.PERMISSION_GUIDANCE, assistantEntryFallback(false, false))
        assertEquals(AssistantEntryFallback.LOCKED_FAILURE, assistantEntryFallback(true, true))
        assertEquals(AssistantEntryFallback.LOCKED_FAILURE, assistantEntryFallback(true, false))
    }
}
