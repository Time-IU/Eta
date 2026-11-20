package io.github.mangi.eta.agent.display

import org.junit.Assert.*
import org.junit.Test

class CanvasFinalLifecycleTest {
    @Test fun viewerCompletionReleasesSameTokenThenSameRunAndNextViewerMayContinue() {
        val lease = CanvasTakeoverLease()
        val token = lease.begin("owner", "s", "viewer1")
        assertTrue(lease.attach(token)); assertEquals(token, lease.current)
        assertTrue(lease.end(token)); assertNull(lease.current)
        val next = lease.begin("owner", "s", "viewer2")
        assertTrue(lease.attach(next)); assertEquals(next, lease.current)
    }
    @Test fun launchDenialAndExceptionRollBackBeforeUnlockAndRemainObservable() {
        for (throws in listOf(false, true)) {
            val lease = CanvasTakeoverLease(); val token = lease.begin("o", "s", "v")
            var invalidatedWithVeto = false
            val result = lease.submit(token, { if (throws) throw SecurityException("denied") else false }, {
                invalidatedWithVeto = lease.current == token
            })
            assertFalse(result); assertTrue(invalidatedWithVeto); assertNull(lease.current)
        }
    }
    @Test fun oldActivityDestroyNeverReleasesNewSessionOrNewTokenOnSameSession() {
        val lease = CanvasTakeoverLease(); val old = lease.begin("o", "s", "old")
        val next = lease.begin("o", "s", "new")
        assertFalse(lease.end(old)); assertEquals(next, lease.current)
        val other = lease.begin("o2", "other", "third")
        assertFalse(lease.end(next)); lease.clearSession("s"); assertEquals(other, lease.current)
    }
    @Test fun unattachedLaunchExpiresButAttachedAndSuccessorDoNot() {
        val lease = CanvasTakeoverLease(); val old = lease.begin("o", "s", "1")
        assertTrue(lease.expireUnattached(old)); assertNull(lease.current)
        val attached = lease.begin("o", "s", "2"); assertTrue(lease.attach(attached))
        assertFalse(lease.expireUnattached(attached))
        val next = lease.begin("o", "s", "3")
        assertFalse(lease.expireUnattached(attached)); assertEquals(next, lease.current)
    }
    @Test fun configRecreationMayAttachSameTokenAndWrongTokenCannotAttach() {
        val lease = CanvasTakeoverLease(); val token = lease.begin("o", "s", "v")
        assertTrue(lease.attach(token)); assertTrue(lease.attach(token))
        assertFalse(lease.attach(token.copy(id = "wrong"))); assertEquals(token, lease.current)
    }
    @Test fun queuedOldViewerInputCannotBeWrittenAfterHandbackOrNewViewer() {
        val lease = CanvasTakeoverLease(); val first = lease.begin("o", "s", "one")
        assertTrue(lease.permitsInput("s", "one")); lease.end(first)
        assertFalse(lease.permitsInput("s", "one"))
        lease.begin("o", "s", "two")
        assertFalse(lease.permitsInput("s", "one")); assertTrue(lease.permitsInput("s", "two"))
        assertFalse(lease.permitsInput("other", "two"))
    }
    @Test fun staticDisplayWithZeroImageReaderCallbacksStillUsesRealNewCapture() {
        var callbacks = 0; var screenshotCalls = 0; var now = 1000L
        val cached = CanvasNewCapturePolicy.Frame("old-static", 400L, 3L)
        // No listener callback occurs. The production demand path must invoke capture itself.
        val frame = CanvasNewCapturePolicy.request(1000, 3, { now }, { 3L }) {
            screenshotCalls++; now = 1030
            CanvasNewCapturePolicy.Frame("new-same-pixels", 1010L, 3L)
        }
        assertEquals(0, callbacks); assertEquals(1, screenshotCalls)
        assertNotEquals(cached, frame); assertEquals("new-same-pixels", frame?.image)
    }
    @Test fun collapsedPreviewDoesNotPreventNextActualCapture() {
        var calls = 0; var now = 3000L
        fun capture() = CanvasNewCapturePolicy.request(now, 4L, { now + 20 }, { 4L }) {
            calls++; CanvasNewCapturePolicy.Frame("static", now + 10, 4L)
        }
        assertNotNull(capture()); now = 9000L; assertNotNull(capture()); assertEquals(2, calls)
    }
    @Test fun deniedCaptureOrOldScreenshotTimestampCannotForgeNewEvidence() {
        assertNull(CanvasNewCapturePolicy.request<String>(1000, 3, { 1050 }, { 3 }) { null })
        assertNull(CanvasNewCapturePolicy.request(1000, 3, { 1050 }, { 3 }) { CanvasNewCapturePolicy.Frame("old", 900, 3) })
        assertNull(CanvasNewCapturePolicy.request(1000, 3, { 1050 }, { 3 }) { CanvasNewCapturePolicy.Frame("future", 1100, 3) })
    }
    @Test fun cancellationOrManualGenerationChangeDuringCaptureDiscardsIt() {
        var generation = 3L
        val result = CanvasNewCapturePolicy.request(1000, 3, { 1050 }, { generation }) {
            generation = 4; CanvasNewCapturePolicy.Frame("new", 1020, 3)
        }
        assertNull(result)
    }
}
