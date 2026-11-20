package io.github.mangi.eta.agent.virtualdisplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class CanvasScreenshotPolicyTest {
    private fun fresh(session: String? = "s", generation: Long? = 3L, captured: Long = 101L,
        frameGeneration: Long = 3L, display: Int = 7): Boolean = CanvasScreenshotPolicy.confirmed(
        "s", session, 3L, generation, 7, display, true, 9L, captured, frameGeneration, 100L, 102L)
    @Test fun acceptsOnlyConfirmedCurrentSessionRequestTimeFrames() {
        assertTrue(fresh())
        assertFalse(fresh(session = "replacement"))
        assertFalse(fresh(session = null))
        assertFalse(fresh(generation = 4L))
        assertFalse(fresh(captured = 99L))
        assertFalse(fresh(captured = 103L))
        assertFalse(fresh(frameGeneration = 2L))
        assertFalse(fresh(display = 8))
    }
    @Test fun mainThreadAndZeroBudgetNeverScheduleOrReturnCache() {
        val scheduled = AtomicInteger()
        val capture = BoundedCanvasCapture<String>({ scheduled.incrementAndGet() }, {})
        assertNull(capture.capture(50L, true) { "old" })
        assertNull(capture.capture(0L, false) { "old" })
        assertEquals(0, scheduled.get())
    }
    @Test fun collapsedPreviewDoesNotPreventAnIndependentRequest() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val capture = BoundedCanvasCapture<String>(executor, {})
            assertEquals("new-request", capture.capture(1000L, false) { "new-request" })
        } finally { executor.shutdownNow() }
    }
    @Test fun timeoutIsBoundedRejectsOverlapAndDisposesLateResult() {
        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val disposed = CountDownLatch(1)
        val capture = BoundedCanvasCapture<String>(executor, { disposed.countDown() })
        try {
            val start = System.nanoTime()
            assertNull(capture.capture(25L, false) { entered.countDown(); release.await(); "late" })
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 1000L)
            assertTrue(entered.await(1000L, TimeUnit.MILLISECONDS))
            assertNull(capture.capture(100L, false) { fail("must not queue"); "queued" })
            release.countDown()
            assertTrue(disposed.await(1000L, TimeUnit.MILLISECONDS))
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun requestExceptionProducesNoFallbackFrame() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val capture = BoundedCanvasCapture<String>(executor, {})
            assertNull(capture.capture(1000L, false) { throw IllegalStateException("no fresh frame") })
        } finally { executor.shutdownNow() }
    }
}
