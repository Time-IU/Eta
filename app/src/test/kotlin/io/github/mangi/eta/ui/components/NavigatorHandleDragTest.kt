package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorHandleDragTest {
    @Test fun upwardThresholdOpensExactlyOnce() {
        val drag = NavigatorHandleDrag(16f)
        drag.add(-8f)
        drag.add(-8f)
        assertTrue(drag.finish())
        assertFalse(drag.finish())
    }

    @Test fun shortUpwardMotionDoesNotOpen() {
        val drag = NavigatorHandleDrag(16f)
        drag.add(-15.9f)
        assertFalse(drag.finish())
    }

    @Test fun downwardMotionDoesNotOpen() {
        val drag = NavigatorHandleDrag(16f)
        drag.add(50f)
        assertFalse(drag.finish())
    }

    @Test fun downThenBackToStartIsNotAnUpwardSwipe() {
        val drag = NavigatorHandleDrag(16f)
        drag.add(40f)
        drag.add(-40f)
        assertFalse(drag.finish())
    }

    @Test fun returningNearStartAfterGoingUpDoesNotOpen() {
        val drag = NavigatorHandleDrag(16f)
        drag.add(-40f)
        drag.add(35f)
        assertFalse(drag.finish())
    }

    @Test fun cancellingDoesNotLeakIntoNextGesture() {
        val drag = NavigatorHandleDrag(16f)
        drag.add(-40f)
        drag.reset()
        assertFalse(drag.finish())
        drag.add(-8f)
        assertFalse(drag.finish())
    }

    @Test fun thresholdTracksDensitySuppliedByCaller() {
        val drag = NavigatorHandleDrag(48f)
        drag.add(-32f)
        assertFalse(drag.finish())
        drag.add(-48f)
        assertTrue(drag.finish())
    }
}
