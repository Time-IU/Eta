package io.github.mangi.eta.ui.screens.memory

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryNoticeVisibilityTest {
    @Test fun coveredRetainedDestinationDoesNotPresentTheSharedNotice() {
        assertFalse(shouldShowMemoryNotice(Lifecycle.State.STARTED, hasNotice = true))
        assertFalse(shouldShowMemoryNotice(Lifecycle.State.CREATED, hasNotice = true))
        assertTrue(shouldShowMemoryNotice(Lifecycle.State.RESUMED, hasNotice = true))
        assertFalse(shouldShowMemoryNotice(Lifecycle.State.RESUMED, hasNotice = false))
    }
}
