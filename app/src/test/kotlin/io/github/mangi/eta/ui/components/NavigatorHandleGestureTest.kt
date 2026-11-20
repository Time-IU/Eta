package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorHandleGestureTest {
    private fun gesture() = NavigatorHandleGesture(16f, 8f)
    @Test fun tapIsOneShot() { val g = gesture(); assertTrue(g.finish()); assertFalse(g.finish()) }
    @Test fun upwardIsOneShot() { val g = gesture(); g.add(0f, -40f); assertTrue(g.ownsVerticalDrag); assertTrue(g.finish()); assertFalse(g.finish()) }
    @Test fun downwardDoesNotBecomeClick() { val g = gesture(); g.add(0f, 40f); assertFalse(g.finish()) }
    @Test fun shortDragDoesNotBecomeClick() { val g = gesture(); g.add(0f, -12f); assertFalse(g.finish()) }
    @Test fun horizontalDoesNotBecomeUpwardNavigation() { val g = gesture(); g.add(40f, 0f); g.add(0f, -80f); assertFalse(g.finish()) }
    @Test fun downThenReturnDoesNotOpen() { val g = gesture(); g.add(0f, 40f); g.add(0f, -40f); assertFalse(g.finish()) }
    @Test fun cancelDropsEvenACompletedUpwardDrag() { val g = gesture(); g.add(0f, -40f); g.cancel(); assertFalse(g.finish()) }
    @Test fun cancellationDoesNotBecomeTap() { val g = gesture(); g.cancel(); assertFalse(g.finish()) }
    @Test fun densityThresholdIsApplied() { val g = NavigatorHandleGesture(48f, 24f); g.add(0f, -32f); assertFalse(g.finish()) }
    @Test fun smallTapJitterIsAllowed() { val g = gesture(); g.add(2f, -3f); assertTrue(g.finish()) }
}
