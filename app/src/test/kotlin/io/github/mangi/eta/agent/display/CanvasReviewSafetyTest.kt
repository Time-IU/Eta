package io.github.mangi.eta.agent.display

import org.junit.Assert.*
import org.junit.Test

class CanvasReviewSafetyTest {
    private fun error(cancelled: Boolean = false, gate: Boolean = true, takeover: Boolean = false,
        owner: String? = "owner", run: String? = "run", session: String? = "session", generation: Long? = 3L): String? =
        CanvasReviewSafety.inputError("owner", "run", "session", 3L, owner, run, session, generation, cancelled, gate, takeover)

    @Test fun everyTextAliasIsDeniedBeforeTheActualWriteForBothTakeoverSources() {
        for (name in listOf("type_text", "input_text", "replace_text", "clear_text", "paste_text")) {
            for (denial in listOf(error(gate = false), error(takeover = true))) {
                var writes = 0
                try { CanvasReviewSafety.dispatchInput({ denial }) { writes++ } ; fail("$name bypassed takeover") }
                catch (expected: IllegalArgumentException) { assertEquals("USER_TAKEOVER", expected.message) }
                assertEquals(name, 0, writes)
            }
        }
    }
    @Test fun cancellationOwnerRunSessionAndGenerationAreRecheckedBeforeWrite() {
        val rejected = listOf(error(cancelled = true), error(owner = "other"), error(run = "next"),
            error(session = "replacement"), error(generation = 4L), error(owner = null))
        for (denial in rejected) {
            assertNotNull(denial)
            var writes = 0
            try { CanvasReviewSafety.dispatchInput({ denial }) { writes++ }; fail("Rejected identity wrote") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, writes)
        }
    }
    @Test fun validOwnedInputActuallyExecutesRatherThanRejectingEverything() {
        var writes = 0
        val result = CanvasReviewSafety.dispatchInput({ error() }) { writes++; "written" }
        assertEquals("written", result)
        assertEquals(1, writes)
    }
    @Test fun queuedWriteChecksAuthorizationAtDispatchNotWhenItWasConstructed() {
        var takeover = false
        val authorize = { error(takeover = takeover) }
        assertNull(authorize())
        takeover = true
        var writes = 0
        try { CanvasReviewSafety.dispatchInput(authorize) { writes++ }; fail("Old authorization reused") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0, writes)
    }
    @Test fun aRetainedSessionAcceptsTheNewRunButRejectsOldRunAndCreatorCancellation() {
        assertNull(CanvasReviewSafety.inputError("o", "new", "s", 4, "o", "new", "s", 4, false, true, false))
        assertEquals("DISPLAY_RUN_MISMATCH", CanvasReviewSafety.inputError("o", "old", "s", 4, "o", "new", "s", 4, false, true, false))
        assertEquals("DISPLAY_CANCELLED", CanvasReviewSafety.inputError("o", "new", "s", 4, "o", "new", "s", 4, true, true, false))
    }
    @Test fun foregroundTaskIsNotLastBackgroundTaskOnTheSameDisplay() {
        val foreground = CanvasReviewSafety.Task(101, 8, true)
        val background = CanvasReviewSafety.Task(99, 8, false)
        assertEquals(101, CanvasReviewSafety.currentTask(listOf(foreground, background), 8)?.id)
        assertNull(CanvasReviewSafety.currentTask(listOf(background), 8))
        assertNull(CanvasReviewSafety.currentTask(listOf(foreground), 9))
    }
    @Test fun samePackageOrOtherVisibleMainTaskIsNotAMigrationReceipt() {
        val wrongMain = CanvasReviewSafety.Task(99, 0, true)
        val originalStillVirtual = CanvasReviewSafety.Task(101, 8, true)
        assertFalse(CanvasReviewSafety.migrated(101, listOf(wrongMain, originalStillVirtual)))
        assertFalse(CanvasReviewSafety.migrated(101, listOf(CanvasReviewSafety.Task(101, 0, false))))
        assertTrue(CanvasReviewSafety.migrated(101, listOf(wrongMain, CanvasReviewSafety.Task(101, 0, true))))
    }
    @Test fun resumedDemandCannotSignOldCachedFrameEvenWithANewObservationId() {
        assertFalse(CanvasReviewSafety.freshFrame(41, 900, 3, 0, 1000, 3, 1010))
        assertTrue(CanvasReviewSafety.freshFrame(42, 1005, 3, 41, 1000, 3, 1010))
    }
    @Test fun afterFrameIdIsStrictAndManualGenerationCannotBeRelabeled() {
        assertFalse(CanvasReviewSafety.freshFrame(42, 1005, 3, 42, 1000, 3, 1010))
        assertFalse(CanvasReviewSafety.freshFrame(43, 1005, 2, 42, 1000, 3, 1010))
        assertTrue(CanvasReviewSafety.freshFrame(43, 1005, 3, 42, 1000, 3, 1010))
    }
    @Test fun futureOrExpiredCaptureCannotAuthorizeCoordinates() {
        assertFalse(CanvasReviewSafety.freshFrame(43, 1015, 3, 42, 1000, 3, 1010))
        assertFalse(CanvasReviewSafety.freshFrame(43, 1005, 3, 42, 1000, 3, 3000))
    }
    @Test fun imageMonotonicTimestampIsTranslatedWithoutInventingAFreshCapture() {
        assertEquals(4980L, CanvasReviewSafety.captureElapsed(980_000_000L, 1_000_000_000L, 5000))
        assertEquals(-1L, CanvasReviewSafety.captureElapsed(0, 1_000_000_000L, 5000))
        assertEquals(-1L, CanvasReviewSafety.captureElapsed(1_010_000_000L, 1_000_000_000L, 5000))
        assertEquals(-1L, CanvasReviewSafety.captureElapsed(1L, 2_000_000_000L, 5000))
    }
    @Test fun realManualEditableFocusEnablesBridgeButAutomaticOrUnknownDoesNot() {
        assertTrue(CanvasReviewSafety.Ime(true, true, true).bridgeRequested)
        assertFalse(CanvasReviewSafety.Ime(true, true, false).bridgeRequested)
        assertFalse(CanvasReviewSafety.Ime(true, false, true).bridgeRequested)
        assertFalse(CanvasReviewSafety.Ime(false, true, true).bridgeRequested)
    }
}
