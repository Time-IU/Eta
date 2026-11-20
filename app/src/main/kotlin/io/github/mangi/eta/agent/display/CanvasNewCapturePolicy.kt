package io.github.mangi.eta.agent.display

/** Demand invokes a real independent capture, including when ImageReader delivers zero callbacks.
 * A capture result is accepted only against the unchanged session generation and actual API time.
 */
internal object CanvasNewCapturePolicy {
    data class Frame<T>(val image: T, val capturedAtMs: Long, val generation: Long)
    fun <T> request(notBeforeMs: Long, expectedGeneration: Long, now: () -> Long,
        generation: () -> Long, capture: () -> Frame<T>?): Frame<T>? {
        if (generation() != expectedGeneration) return null
        val frame = capture() ?: return null
        val time = now()
        return frame.takeIf { generation() == expectedGeneration && it.generation == expectedGeneration &&
            it.capturedAtMs >= notBeforeMs && it.capturedAtMs <= time && time - it.capturedAtMs <= 1500L }
    }
}
