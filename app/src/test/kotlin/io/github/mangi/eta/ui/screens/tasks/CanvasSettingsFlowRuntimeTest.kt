package io.github.mangi.eta.ui.screens.tasks

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Compose runtime + cold Flow, no simulated recomposition counter or Android graphics. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class CanvasSettingsFlowRuntimeTest {
    private class EmptyApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private data class Counts(var subscriptions: Int = 0, var disposed: Int = 0,
        var emissions: Int = 0, var recompositions: Int = 0, var defaultsAfterRead: Int = 0)

    private fun scenario(stable: Boolean, lifecycleAware: Boolean = false): Counts = runBlocking {
        withTimeout(5000) {
            val counts = Counts()
            val tick = mutableIntStateOf(0)
            val frames = BroadcastFrameClock()
            val recomposer = Recomposer(coroutineContext + frames)
            val runner = launch(frames, start = CoroutineStart.UNDISPATCHED) { recomposer.runRecomposeAndApplyChanges() }
            val composition = Composition(EmptyApplier(), recomposer)
            val owner = object : LifecycleOwner {
                val registry = LifecycleRegistry.createUnsafe(this)
                override val lifecycle: Lifecycle get() = registry
            }
            owner.registry.currentState = Lifecycle.State.STARTED
            var sawRead = false
            fun factory(): Flow<Int> = flow {
                counts.subscriptions++
                try { counts.emissions++; emit(7); awaitCancellation() }
                finally { counts.disposed++ }
            }
            composition.setContent {
                val changed = tick.intValue
                val flow = if (stable) rememberCanvasSettingsFlow { factory() } else factory()
                val saved by if (lifecycleAware) flow.collectAsStateWithLifecycle(
                    initialValue = -1, lifecycle = owner.lifecycle) else flow.collectAsState(initial = -1)
                SideEffect {
                    counts.recompositions++
                    if (saved == 7) sawRead = true
                    if (sawRead && saved == -1) counts.defaultsAfterRead++
                    check(changed >= 0)
                }
            }
            suspend fun pump() { repeat(5) { Snapshot.sendApplyNotifications(); frames.sendFrame(it.toLong()); yield() } }
            pump()
            repeat(6) { tick.intValue++; pump() }
            if (lifecycleAware) {
                owner.registry.currentState = Lifecycle.State.CREATED
                pump()
                assertEquals(1, counts.disposed)
                owner.registry.currentState = Lifecycle.State.STARTED
                pump()
                assertEquals(2, counts.subscriptions)
            }
            composition.dispose(); recomposer.close(); runner.join()
            counts
        }
    }

    @Test fun rememberedFlowOwnsOneSubscriptionAcrossRealRecompositions() {
        val stable = scenario(true)
        assertEquals(1, stable.subscriptions)
        assertEquals(1, stable.emissions)
        assertEquals(1, stable.disposed)
        assertTrue(stable.recompositions >= 7)
        assertEquals(0, stable.defaultsAfterRead)
        println("STABLE $stable")
    }

    @Test fun actualLifecycleCollectorStopsResumesAndRetainsSavedState() {
        val lifecycle = scenario(stable = true, lifecycleAware = true)
        assertEquals(2, lifecycle.subscriptions)
        assertEquals(2, lifecycle.disposed)
        assertEquals(0, lifecycle.defaultsAfterRead)
        println("LIFECYCLE $lifecycle")
    }

    @Test fun negativeControlColdFactoryReallyResubscribesButDoesNotResetRememberedState() {
        val cold = scenario(false)
        assertTrue(cold.subscriptions >= 7)
        assertEquals(cold.subscriptions, cold.emissions)
        assertEquals(cold.subscriptions, cold.disposed)
        // collectAsState's produceState remembers the initial value. Replacing its Flow
        // does NOT by itself prove a Settings() reset or prove the recorded visual flicker.
        assertEquals(0, cold.defaultsAfterRead)
        println("COLD_NEGATIVE $cold")
    }

    @Test fun projectionSuppressesUnrelatedWritesButRetainsEachControlChange() = runBlocking {
        data class Source(val canvas: Boolean, val unrelated: Int)
        val output = flowOf(Source(false,0),Source(false,1),Source(false,2),
            Source(true,2),Source(true,3),Source(false,3))
            .distinctCanvasSettings { it.canvas }.toList()
        assertEquals(listOf(false, true, false), output)
        println("PROJECTED $output from 6 real emissions")
    }

    @Test fun negativeControlRemovingDistinctLeaksUnrelatedWrites() = runBlocking {
        val output = flowOf(0,1,2,3).map { false }.toList()
        assertEquals(4, output.size)
        assertNotEquals(listOf(false), output)
    }
}
