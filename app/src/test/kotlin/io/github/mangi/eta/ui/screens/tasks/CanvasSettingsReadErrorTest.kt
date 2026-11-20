package io.github.mangi.eta.ui.screens.tasks

import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** Executes the same reader/state flow consumed by the canvas page, with real thrown IO errors. */
class CanvasSettingsReadErrorTest {
    private suspend fun settle() { repeat(12) { yield() } }

    @Test fun ioBeforeFirstReadNeverExposesDefaultsAndRetryResubscribes() = runBlocking {
        var attempts = 0
        val reader = CanvasSettingsReader {
            flow {
                if (++attempts == 1) throw IOException("first read")
                emit("actual-saved-value"); awaitCancellation()
            }
        }
        val events = mutableListOf<CanvasSettingsReadState<String>>()
        val observer = launch(start = CoroutineStart.UNDISPATCHED) { reader.state.toList(events) }
        val job = launch { reader.collect() }
        settle()
        assertEquals(1, attempts)
        assertTrue(reader.state.value.failed)
        assertNull(reader.state.value.snapshot)
        assertFalse(reader.state.value.canEdit)
        assertTrue(job.isActive) // failed inner Flow must not kill the retry token collector
        reader.retry(); settle()
        assertEquals(2, attempts)
        assertEquals("actual-saved-value", reader.state.value.snapshot)
        assertTrue(reader.state.value.canEdit)
        assertTrue(events.any { it.failed && it.snapshot == null })
        println("BEFORE_FIRST $events attempts=$attempts")
        job.cancelAndJoin(); observer.cancelAndJoin()
    }

    @Test fun ioAfterKnownGoodRetainsSnapshotAndFailedRetryCannotEnableWrites() = runBlocking {
        var attempts = 0
        val failKnown = CompletableDeferred<Unit>()
        val retryRead = CompletableDeferred<Unit>()
        val reader = CanvasSettingsReader {
            flow {
                when (++attempts) {
                    1 -> { emit(42); failKnown.await(); throw IOException("after saved value") }
                    2 -> throw IOException("retry still broken")
                    else -> { retryRead.await(); emit(84); awaitCancellation() }
                }
            }
        }
        val job = launch { reader.collect() }; settle()
        assertEquals(42, reader.state.value.snapshot)
        assertTrue(reader.state.value.canEdit)
        failKnown.complete(Unit); settle()
        assertEquals(42, reader.state.value.snapshot)
        assertTrue(reader.state.value.failed)
        assertFalse(reader.state.value.canEdit)
        reader.retry(); settle()
        assertEquals(2, attempts)
        assertEquals(42, reader.state.value.snapshot)
        assertTrue(reader.state.value.failed)
        assertFalse(reader.state.value.canEdit)
        reader.retry(); settle()
        assertTrue(reader.state.value.reading)
        assertEquals(42, reader.state.value.snapshot)
        assertFalse(reader.state.value.canEdit)
        retryRead.complete(Unit); settle()
        assertEquals(84, reader.state.value.snapshot)
        assertTrue(reader.state.value.canEdit)
        assertFalse(reader.state.value.failed)
        println("AFTER_GOOD saved=42 failure/retry retained 42; successful retry=84 attempts=$attempts")
        job.cancelAndJoin()
    }

    @Test fun lifecycleRestartRetainsKnownSnapshotUntilFreshRead() = runBlocking {
        var attempts = 0
        val resumeRead = CompletableDeferred<Unit>()
        val reader = CanvasSettingsReader {
            flow {
                if (++attempts > 1) resumeRead.await()
                emit(false); awaitCancellation()
            }
        }
        var job = launch { reader.collect() }; settle()
        assertEquals(false, reader.state.value.snapshot)
        job.cancelAndJoin()
        job = launch { reader.collect() }; settle()
        assertEquals(false, reader.state.value.snapshot)
        assertTrue(reader.state.value.reading)
        assertFalse(reader.state.value.canEdit)
        resumeRead.complete(Unit); settle()
        assertTrue(reader.state.value.canEdit)
        job.cancelAndJoin()
    }

    @Test fun cancellationIsNotReadFailure() = runBlocking {
        val reader = CanvasSettingsReader<Int> { flow { throw CancellationException("cancel read") } }
        val job = launch { reader.collect() }; settle()
        assertFalse(reader.state.value.failed)
        assertFalse(reader.state.value.canEdit)
        job.cancelAndJoin()
    }

    @Test fun negativeControlLegacyEmptyFallbackReallyExposesWritableDefault() = runBlocking {
        val source = flow<Int> { throw IOException("disk") }.catch { emit(0) }
        val reader = CanvasSettingsReader { source }
        val job = launch { reader.collect() }; settle()
        assertEquals(0, reader.state.value.snapshot)
        assertTrue(reader.state.value.canEdit)
        assertFalse(reader.state.value.failed)
        println("LEGACY_NEGATIVE actual IOException swallowed -> default 0 becomes editable")
        job.cancelAndJoin()
    }
}
