package io.github.mangi.eta.agent.runtime

import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** Real recorder, active registry, recovery projection, memory gate and controller.
 * Only persistence/memory readings/wait sampling are injected; no Android runtime required.
 */
class AgentMemoryFreezeBoundaryTest {
    private fun delta(text: String) = AgentEvent.AssistantBlockDelta(
        1, AgentEvent.AssistantBlockKind.TEXT, 0, text.length, text,
    )

    @Test fun twoFreezeRecoveryCyclesKeepRecorderRegisteredAndThirdFlushStillWorks() {
        val rows = mutableListOf<Pair<Int, AgentEvent>>()
        val recorder = AgentRunCheckpointRecorder.register(UUID.randomUUID().toString(), { 0L },
            writeEvent = { index, event -> rows += index to event })
        val tier = AtomicInteger(AgentMemoryGate.TIER_FREEZE)
        val flushes = AtomicInteger()
        val gate = AgentMemoryWorkGate(
            readTier = tier::get,
            flushActive = { AgentRunCheckpointRecorder.flushActive(); flushes.incrementAndGet() },
            waitForSample = { tier.set(AgentMemoryGate.TIER_NORMAL) },
        )
        val controller = AgentRunController()
        try {
            recorder.accept(delta("first"))
            gate.await(controller)
            assertEquals(listOf("first"), rows.map { (it.second as AgentEvent.AssistantBlockDelta).delta })
            assertFalse(controller.isPaused)
            tier.set(AgentMemoryGate.TIER_FREEZE)
            recorder.accept(delta("second"))
            gate.await(controller)
            assertEquals(2, flushes.get())
            assertEquals(listOf(0, 1), rows.map { it.first })
            assertEquals(listOf("first", "second"), rows.map { (it.second as AgentEvent.AssistantBlockDelta).delta })
            recorder.accept(delta("third"))
            assertTrue(AgentRunCheckpointRecorder.flushActive().flushed >= 1)
            assertEquals(listOf(0, 1, 2), rows.map { it.first })
            assertEquals("third", (rows.last().second as AgentEvent.AssistantBlockDelta).delta)
        } finally { recorder.seal() }
        val finalRows = rows.size
        recorder.accept(delta("must-not-revive-sealed-run"))
        AgentRunCheckpointRecorder.flushActive()
        assertEquals(finalRows, rows.size)
    }

    @Test fun failedFlushRetainsPendingDataAndSortIndexForRetry() {
        val rows = mutableListOf<Pair<Int, AgentEvent>>()
        var fail = true
        val recorder = AgentRunCheckpointRecorder.register(UUID.randomUUID().toString(), { 0L },
            writeEvent = { index, event ->
                if (fail) { fail = false; throw IllegalStateException("injected persistence failure") }
                rows += index to event
            })
        try {
            recorder.accept(delta("durable-after-retry"))
            assertTrue(AgentRunCheckpointRecorder.flushActive().failed >= 1)
            assertTrue(rows.isEmpty())
            assertEquals(0, AgentRunCheckpointRecorder.flushActive().failed)
            assertEquals(listOf(0), rows.map { it.first })
            assertEquals("durable-after-retry", (rows.single().second as AgentEvent.AssistantBlockDelta).delta)
        } finally { recorder.seal() }
    }

    @Test fun queueBlocksNewRequestAndHeavyToolEvenWithFreeSessionSlots() {
        for (boundary in listOf(AgentMemoryWorkGate.Boundary.REQUEST, AgentMemoryWorkGate.Boundary.HEAVY_TOOL)) {
            val tier = AtomicInteger(AgentMemoryGate.TIER_QUEUE)
            var waits = 0
            var issued = false
            val gate = AgentMemoryWorkGate(tier::get, {}, {
                assertFalse(issued)
                waits++
                tier.set(AgentMemoryGate.TIER_NORMAL)
            })
            gate.await(AgentRunController(), boundary)
            issued = true
            assertTrue(issued)
            assertEquals(1, waits)
        }
        assertFalse(AgentMemoryWorkGate.blocks(AgentMemoryGate.TIER_QUEUE, AgentMemoryWorkGate.Boundary.LIGHT_TOOL))
        assertTrue(AgentMemoryWorkGate.blocks(AgentMemoryGate.TIER_FREEZE, AgentMemoryWorkGate.Boundary.LIGHT_TOOL))
    }

    @Test fun freezeRecoveryNeverResumesExplicitUserPauseOrOtherRun() {
        val userPaused = AgentRunController().apply { pause() }
        val otherPaused = AgentRunController().apply { pause() }
        val sampled = CountDownLatch(1)
        val tier = AtomicInteger(AgentMemoryGate.TIER_FREEZE)
        val gate = AgentMemoryWorkGate(
            readTier = { if (tier.get() == AgentMemoryGate.TIER_NORMAL) sampled.countDown(); tier.get() },
            flushActive = {},
            waitForSample = { tier.set(AgentMemoryGate.TIER_NORMAL) },
        )
        val worker = Executors.newSingleThreadExecutor()
        try {
            val waiting = worker.submit { gate.await(userPaused) }
            assertTrue(sampled.await(2, TimeUnit.SECONDS))
            try { waiting.get(100, TimeUnit.MILLISECONDS); fail("memory gate cleared a user's pause") }
            catch (_: TimeoutException) { /* still paused, as required */ }
            assertTrue(userPaused.isPaused)
            assertTrue(otherPaused.isPaused)
            userPaused.resume()
            waiting.get(2, TimeUnit.SECONDS)
            assertTrue(otherPaused.isPaused)
        } finally { userPaused.cancel(); otherPaused.cancel(); worker.shutdownNow() }
    }

    @Test fun cancellationLeavesFreezeWaitWithoutIssuingNewWork() {
        val controller = AgentRunController()
        val sampled = CountDownLatch(1)
        val gate = AgentMemoryWorkGate({ AgentMemoryGate.TIER_FREEZE }, {}, {
            sampled.countDown(); it.awaitRetryDelay(10_000)
        })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val waiting = worker.submit<Boolean> {
                try { gate.await(controller); false } catch (_: AgentRunCancelledException) { true }
            }
            assertTrue(sampled.await(2, TimeUnit.SECONDS))
            controller.cancel()
            assertTrue(waiting.get(2, TimeUnit.SECONDS))
        } finally { controller.cancel(); worker.shutdownNow() }
    }

    @Test fun concurrentAcceptAndRepeatedFreezeFlushKeepAllTextAndUniqueIndices() {
        val rows = mutableListOf<Pair<Int, AgentEvent>>()
        val recorder = AgentRunCheckpointRecorder.register(UUID.randomUUID().toString(), { 0L },
            writeEvent = { index, event -> rows += index to event })
        val worker = Executors.newFixedThreadPool(2)
        try {
            val writer = worker.submit { repeat(2000) { recorder.accept(delta("x")) } }
            val freezer = worker.submit { repeat(500) { AgentRunCheckpointRecorder.flushActive() } }
            writer.get(5, TimeUnit.SECONDS)
            freezer.get(5, TimeUnit.SECONDS)
            recorder.seal()
            assertEquals(2000, rows.sumOf { (it.second as AgentEvent.AssistantBlockDelta).delta.length })
            assertEquals(rows.indices.toList(), rows.map { it.first })
        } finally { recorder.seal(); worker.shutdownNow() }
    }

    @Test fun memoryIsResampledAfterUserPauseBeforeNewAllocation() {
        val controller = AgentRunController().apply { pause() }
        val tier = AtomicInteger(AgentMemoryGate.TIER_NORMAL)
        val sampled = CountDownLatch(1)
        val queued = CountDownLatch(1)
        val allowRecovery = CountDownLatch(1)
        val gate = AgentMemoryWorkGate({ sampled.countDown(); tier.get() }, {}, {
            queued.countDown()
            assertTrue(allowRecovery.await(2, TimeUnit.SECONDS))
            tier.set(AgentMemoryGate.TIER_NORMAL)
        })
        val worker = Executors.newSingleThreadExecutor()
        try {
            val waiting = worker.submit { gate.await(controller) }
            assertTrue(sampled.await(2, TimeUnit.SECONDS))
            tier.set(AgentMemoryGate.TIER_FREEZE)
            controller.resume()
            assertTrue(queued.await(2, TimeUnit.SECONDS))
            assertFalse(waiting.isDone)
            allowRecovery.countDown()
            waiting.get(2, TimeUnit.SECONDS)
        } finally { allowRecovery.countDown(); controller.cancel(); worker.shutdownNow() }
    }
    @Test fun blockedPersistenceDoesNotHoldRegistryLockOrDeadlockConcurrentSeal() {
        val writing = CountDownLatch(1)
        val finishWrite = CountDownLatch(1)
        val rows = AtomicInteger()
        val recorder = AgentRunCheckpointRecorder.register(UUID.randomUUID().toString(), { 0L },
            writeEvent = { _, _ ->
                writing.countDown()
                assertTrue(finishWrite.await(2, TimeUnit.SECONDS))
                rows.incrementAndGet()
            })
        val worker = Executors.newFixedThreadPool(3)
        try {
            recorder.accept(delta("before-seal"))
            val flushing = worker.submit { AgentRunCheckpointRecorder.flushActive() }
            assertTrue(writing.await(2, TimeUnit.SECONDS))
            val sealing = worker.submit { recorder.seal() }
            val independent = worker.submit {
                val other = AgentRunCheckpointRecorder.register(UUID.randomUUID().toString(), { 0L },
                    writeEvent = { _, _ -> })
                other.accept(delta("other-run"))
                other.seal()
            }
            independent.get(1, TimeUnit.SECONDS)
            finishWrite.countDown()
            flushing.get(2, TimeUnit.SECONDS)
            sealing.get(2, TimeUnit.SECONDS)
            recorder.accept(delta("after-seal-must-not-persist"))
            AgentRunCheckpointRecorder.flushActive()
            assertEquals(1, rows.get())
        } finally { finishWrite.countDown(); recorder.seal(); worker.shutdownNow() }
    }

}
