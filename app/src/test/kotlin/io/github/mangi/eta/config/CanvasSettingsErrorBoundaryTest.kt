package io.github.mangi.eta.config

import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CanvasSettingsErrorBoundaryTest {
    private enum class State { FAILED, CONFIRMED, UNAVAILABLE }

    @Test fun failedMirrorAfterLocalSaveDoesNotEscapeUiLaunch() = runBlocking {
        var saved = false
        var state = State.UNAVAILABLE
        var uiError: Exception? = null
        // Same production boundary called after DS edit and in the canvas UI launch.
        val task = launch {
            CanvasSettingsErrorBoundary.action({ uiError = it }) {
                saved = true
                state = CanvasSettingsErrorBoundary.mirror(State.FAILED, {}) {
                    UnifiedCanvasGatePolicy.mirror(true, { false }, { true })
                    State.CONFIRMED
                }
            }
        }
        task.join()
        assertFalse(task.isCancelled)
        assertTrue(saved)
        assertEquals(State.FAILED, state)
        assertNull(uiError) // remote failure is typed state, not local-save failure
    }
    @Test fun unrelatedSaveSurvivesBinderFaultAndRetainsItsValue() = runBlocking {
        var unrelatedValue = 1
        var mirrorError: Exception? = null
        val task = launch {
            unrelatedValue = 2
            val result = CanvasSettingsErrorBoundary.mirror(State.FAILED, { mirrorError = it }) {
                throw SecurityException("Binder unavailable")
            }
            assertEquals(State.FAILED, result)
        }
        task.join()
        assertFalse(task.isCancelled)
        assertEquals(2, unrelatedValue)
        assertTrue(mirrorError is SecurityException)
    }
    @Test fun localDisableHappensBeforeMirrorAndRemoteTrueIsNotFalselyRevoked() = runBlocking {
        var locallyRevoked = false
        val oldRemote = true
        locallyRevoked = true
        val state = CanvasSettingsErrorBoundary.mirror(State.FAILED, {}) {
            assertTrue(locallyRevoked)
            UnifiedCanvasGatePolicy.mirror(false, { false }, { oldRemote })
            State.CONFIRMED
        }
        assertEquals(State.FAILED, state)
        assertTrue(oldRemote)
    }
    @Test fun unavailableRootOnlyIsNotConfirmedAndRetryCanConfirm() = runBlocking {
        val offline = CanvasSettingsErrorBoundary.mirror(State.FAILED, {}) { State.UNAVAILABLE }
        assertEquals(State.UNAVAILABLE, offline)
        var remote: Boolean? = null
        val retry = CanvasSettingsErrorBoundary.mirror(State.FAILED, {}) {
            UnifiedCanvasGatePolicy.mirror(true, { remote = it; true }, { remote })
            State.CONFIRMED
        }
        assertEquals(State.CONFIRMED, retry)
    }
    @Test fun readbackMismatchIsFailedNotSavedRemoteSuccess() = runBlocking {
        assertEquals(State.FAILED, CanvasSettingsErrorBoundary.mirror(State.FAILED, {}) {
            UnifiedCanvasGatePolicy.mirror(false, { true }, { true })
            State.CONFIRMED
        })
    }
    @Test fun actualLocalSaveErrorIsPresentedByUiBoundary() = runBlocking {
        var presented: Exception? = null
        val task = launch {
            CanvasSettingsErrorBoundary.action({ presented = it }) { throw IOException("disk") }
        }
        task.join()
        assertFalse(task.isCancelled)
        assertTrue(presented is IOException)
    }
    @Test fun mirrorCancellationPropagatesWithoutFailureOrConfirmation() = runBlocking {
        var failed = false
        try {
            CanvasSettingsErrorBoundary.mirror(State.FAILED, { failed = true }) {
                throw CancellationException("cancelled")
            }
            fail("cancellation swallowed")
        } catch (_: CancellationException) { }
        assertFalse(failed)
    }
    @Test fun uiCancellationPropagatesWithoutSaveError() = runBlocking {
        var presented = false
        val task = launch {
            CanvasSettingsErrorBoundary.action({ presented = true }) { awaitCancellation() }
        }
        yield()
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertFalse(presented)
    }
}
