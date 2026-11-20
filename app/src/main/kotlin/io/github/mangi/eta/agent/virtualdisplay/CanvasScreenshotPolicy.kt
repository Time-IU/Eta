package io.github.mangi.eta.agent.virtualdisplay

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Exactly one pending capture; UI callers never wait. Timed-out results retain worker ownership. */
internal class BoundedCanvasCapture<T : Any>(private val executor: Executor, private val dispose: (T) -> Unit) {
    private val busy = AtomicBoolean(false)
    fun capture(waitMs: Long, mainThread: Boolean, request: () -> T?): T? {
        if (mainThread || waitMs <= 0 || !busy.compareAndSet(false, true)) return null
        val lock = Any()
        val ready = CountDownLatch(1)
        var abandoned = false
        var value: T? = null
        try {
            executor.execute {
                try {
                    val result = request()
                    synchronized(lock) {
                        if (abandoned) result?.let(dispose) else value = result
                    }
                } catch (_: Exception) {
                    // Null is an explicit failure, never a cached success.
                } finally { busy.set(false); ready.countDown() }
            }
        } catch (_: Exception) { busy.set(false); return null }
        var completed = false
        try { completed = ready.await(waitMs.coerceAtMost(2000L), TimeUnit.MILLISECONDS) }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        return synchronized(lock) {
            if (!completed) { abandoned = true; value?.let(dispose); value = null }
            value.also { value = null }
        }
    }
}

internal object CanvasScreenshotPolicy {
    fun confirmed(requestSession: String, currentSession: String?, requestGeneration: Long,
        currentGeneration: Long?, expectedDisplay: Int, frameDisplay: Int, ok: Boolean,
        frameId: Long, capturedAt: Long, frameGeneration: Long, started: Long, now: Long): Boolean =
        requestSession == currentSession && requestGeneration == currentGeneration &&
            frameDisplay == expectedDisplay && ok && frameId > 0 && capturedAt >= started &&
            capturedAt <= now && now - capturedAt <= 1500L && frameGeneration == requestGeneration
}
