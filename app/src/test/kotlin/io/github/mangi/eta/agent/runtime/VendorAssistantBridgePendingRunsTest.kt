package io.github.mangi.eta.agent.runtime

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class VendorAssistantBridgePendingRunsTest {
    private val id = "vendor:breeno:10123:turn"

    @Test fun cancellationWhilePreparationBlockedNeverStartsAfterPreparationIsReleased() {
        val pending = VendorAssistantBridgePendingRuns()
        val run = pending.register(id) // registration precedes worker submission
        val preparing = CountDownLatch(1)
        val releasePreparation = CountDownLatch(1)
        val starts = AtomicBoolean(false)
        val worker = checkedThread {
            preparing.countDown()
            await(releasePreparation)
            assertFalse(pending.start(run, {}, { starts.set(true) }))
            pending.finish(run)
        }
        await(preparing)
        assertTrue(pending.cancel(id))
        releasePreparation.countDown()
        worker.finish()
        assertFalse(starts.get())
    }

    @Test fun cancellationWhileConnectionIsBlockedCannotBeLostBeforeStartSend() {
        val pending = VendorAssistantBridgePendingRuns()
        val run = pending.register(id)
        val connecting = CountDownLatch(1)
        val connected = CountDownLatch(1)
        val messages = Collections.synchronizedList(mutableListOf<String>())
        val worker = checkedThread {
            connecting.countDown()
            await(connected) // models AgentRuntimeConnection.acquire, outside the gate
            assertFalse(pending.start(run, { messages += "CANCEL" }, { messages += "START" }))
        }
        await(connecting)
        pending.cancel(id)
        connected.countDown()
        worker.finish()
        assertTrue(messages.isEmpty())
    }

    @Test fun cancelRacingActualStartSendIsEnqueuedAfterStartOnTheSameQueue() {
        val pending = VendorAssistantBridgePendingRuns()
        val run = pending.register(id)
        val inStartSend = CountDownLatch(1)
        val releaseStartSend = CountDownLatch(1)
        val cancelAttempted = CountDownLatch(1)
        val messages = Collections.synchronizedList(mutableListOf<String>())
        val starter = checkedThread {
            assertTrue(pending.start(run, { messages += "CANCEL" }, {
                inStartSend.countDown()
                await(releaseStartSend)
                messages += "START"
            }))
        }
        await(inStartSend)
        val canceller = checkedThread {
            cancelAttempted.countDown()
            assertTrue(pending.cancel(id))
        }
        await(cancelAttempted)
        releaseStartSend.countDown()
        starter.finish()
        canceller.finish()
        assertEquals(listOf("START", "CANCEL"), messages)
        // Models Runtime's single Handler: registration must exist by the time CANCEL runs.
        var registered = false
        messages.forEach { what -> if (what == "START") registered = true else {
            assertTrue(registered)
            registered = false
        } }
        assertFalse(registered)
    }

    @Test fun duplicateIdCannotReplaceOrReviveACancelledPreparingWorker() {
        val pending = VendorAssistantBridgePendingRuns()
        val run = pending.register(id)
        assertThrows(IllegalArgumentException::class.java) { pending.register(id) }
        pending.cancel(id)
        assertThrows(IllegalArgumentException::class.java) { pending.register(id) }
        assertFalse(pending.start(run, {}, { fail("Cancelled duplicate started") }))
        pending.finish(run)
        val replacement = pending.register(id)
        pending.finish(run) // stale finally must not unregister replacement
        assertTrue(pending.start(replacement, {}, {}))
    }

    @Test fun destroyRevokesPreparingAndStartedRunsAndRejectsQueuedRegistration() {
        val pending = VendorAssistantBridgePendingRuns()
        val preparing = pending.register(id)
        val active = pending.register("$id-active")
        val messages = mutableListOf<String>()
        assertTrue(pending.start(active, { messages += "CANCEL" }, { messages += "START" }))
        pending.destroy()
        pending.destroy()
        assertEquals(listOf("START", "CANCEL"), messages)
        assertTrue(pending.isCancelled(preparing))
        assertTrue(pending.isCancelled(active))
        assertFalse(pending.start(preparing, {}, { fail("Destroyed request started") }))
        assertThrows(IllegalStateException::class.java) { pending.register("$id-queued") }
        pending.finish(preparing)
        pending.finish(active)
    }

    @Test fun rejectedWorkerSubmissionCanReleaseRegistrationWithoutStarting() {
        val pending = VendorAssistantBridgePendingRuns()
        val rejected = pending.register(id)
        pending.finish(rejected) // service submission catch/finally
        assertFalse(pending.start(rejected, {}, { fail("Rejected worker started") }))
        assertNotNull(pending.register(id))
    }

    @Test fun repeatedCancellationSendsOnlyOneRuntimeCancel() {
        val pending = VendorAssistantBridgePendingRuns()
        val run = pending.register(id)
        val messages = mutableListOf<String>()
        pending.start(run, { messages += "CANCEL" }, { messages += "START" })
        pending.cancel(id)
        pending.cancel(id)
        pending.destroy()
        assertEquals(listOf("START", "CANCEL"), messages)
    }

    @Test fun ambiguousStartSendFailureStillCancelsBeforeReleasingTheGate() {
        val pending = VendorAssistantBridgePendingRuns()
        val run = pending.register(id)
        val messages = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            pending.start(run, { messages += "CANCEL" }, {
                messages += "START"
                error("Ambiguous transport failure after enqueue")
            })
        }
        assertEquals(listOf("START", "CANCEL"), messages)
        assertTrue(pending.isCancelled(run))
    }

    @Test fun destroyWhilePreparationBlockedNeverStartsWhenWorkerResumes() {
        val pending = VendorAssistantBridgePendingRuns()
        val run = pending.register(id)
        val preparing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = checkedThread {
            preparing.countDown()
            await(release)
            assertFalse(pending.start(run, {}, { fail("Destroyed preparation started") }))
            pending.finish(run)
        }
        await(preparing)
        pending.destroy()
        release.countDown()
        worker.finish()
    }

    @Test fun oneRegistrationCannotEnqueueStartTwiceOrCancelAnotherId() {
        val pending = VendorAssistantBridgePendingRuns()
        val run = pending.register(id)
        val other = pending.register("$id-other")
        val messages = mutableListOf<String>()
        assertTrue(pending.start(run, { messages += "CANCEL" }, { messages += "START" }))
        assertFalse(pending.start(run, {}, { fail("Duplicate START") }))
        pending.cancel(id)
        assertFalse(pending.isCancelled(other))
        assertEquals(listOf("START", "CANCEL"), messages)
    }

    @Test fun failedCancelSendStillRevokesTheTokenAndCannotRevivePendingWork() {
        val pending = VendorAssistantBridgePendingRuns()
        val run = pending.register(id)
        pending.start(run, { error("dead binder") }, {})
        assertTrue(pending.cancel(id))
        assertTrue(pending.isCancelled(run))
        assertFalse(pending.start(run, {}, { fail("Cancelled token revived") }))
    }

    private fun await(latch: CountDownLatch) = assertTrue("Timed out waiting for test barrier", latch.await(5, TimeUnit.SECONDS))

    private fun checkedThread(body: () -> Unit): CheckedThread {
        val failure = AtomicReference<Throwable?>()
        val thread = Thread { try { body() } catch (t: Throwable) { failure.set(t) } }
        thread.start()
        return CheckedThread(thread, failure)
    }

    private class CheckedThread(val thread: Thread, val failure: AtomicReference<Throwable?>) {
        fun finish() {
            thread.join(6000)
            assertFalse("Worker failed to terminate", thread.isAlive)
            failure.get()?.let { throw AssertionError("Worker assertion failed", it) }
        }
    }
}
