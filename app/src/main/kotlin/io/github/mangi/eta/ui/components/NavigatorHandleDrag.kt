package io.github.mangi.eta.ui.components

/** Signed displacement after Compose's vertical touch slop; no Compose or Android dependency. */
internal class NavigatorHandleDrag(private val triggerPx: Float) {
    private var displacement = 0f

    fun reset() {
        displacement = 0f
    }

    fun add(amount: Float) {
        // Keep positive displacement, too: down then back to the starting point is NOT up.
        displacement += amount
    }

    fun finish(): Boolean {
        val open = displacement <= -triggerPx
        reset()
        return open
    }
}

/** Total displacement from DOWN. Shared by the real pointer loop and JVM regression tests. */
internal class NavigatorHandleGesture(private val triggerPx: Float, private val touchSlop: Float) {
    private var dx = 0f
    private var dy = 0f
    private var moved = false
    private var finished = false
    private var horizontal = false
    val ownsVerticalDrag get() = !finished && !horizontal && kotlin.math.abs(dy) > touchSlop && kotlin.math.abs(dy) > kotlin.math.abs(dx)

    fun add(x: Float, y: Float) {
        if (finished) return
        dx += x
        dy += y
        if (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop) moved = true
        if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) horizontal = true
    }

    fun cancel() { finished = true }
    fun finish(): Boolean {
        if (finished) return false
        val open = !horizontal && (!moved || (dy <= -triggerPx && kotlin.math.abs(dy) > kotlin.math.abs(dx)))
        finished = true
        return open
    }
}
