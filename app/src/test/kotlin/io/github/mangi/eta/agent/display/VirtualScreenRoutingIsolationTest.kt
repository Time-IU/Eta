package io.github.mangi.eta.agent.display

import org.junit.Assert.*
import org.junit.Test

class VirtualScreenRoutingIsolationTest {
    @Test fun retainedOwnerADoesNotChangeOwnerBExplicitMain() {
        val ownerA = VirtualScreenRoutingPolicy(initiallyRouted = true)
        val ownerB = VirtualScreenRoutingPolicy()
        ownerB.selectMain()
        assertTrue(ownerA.shouldRoute("observe_screen", true))
        for (tool in listOf("observe_screen", "tap", "tap_element", "open_uri")) {
            assertFalse(tool, ownerB.shouldRoute(tool, true))
        }
        assertEquals(VirtualScreenRoutingPolicy.Target.MAIN, ownerB.target)
        assertEquals(0, ownerB.displayId)
    }

    @Test fun ownerBAutoChosenCanvasRemainsItsCanvas() {
        val ownerA = VirtualScreenRoutingPolicy(true)
        val ownerB = VirtualScreenRoutingPolicy()
        ownerB.selectCanvas(19)
        assertTrue(ownerA.shouldRoute("tap", true))
        repeat(4) { assertFalse(ownerB.shouldRoute("observe_screen", true)) }
        assertEquals(19, ownerB.displayId)
        assertEquals(VirtualScreenRoutingPolicy.Target.CANVAS, ownerB.target)
    }

    @Test fun routeChangesOnlyAfterAnExplicitSuccessfulSelection() {
        val route = VirtualScreenRoutingPolicy()
        assertFalse(route.shouldRoute("lingscreen_open_app", true))
        // Merely asking about the entry does not commit a failed launch to a new screen.
        assertEquals(VirtualScreenRoutingPolicy.Target.MAIN, route.target)
        route.selectVirtual()
        for (tool in listOf("observe_screen", "tap", "long_press_element", "type_text")) {
            assertTrue(tool, route.shouldRoute(tool, true))
        }
        route.selectMain()
        assertFalse(route.shouldRoute("observe_screen", true))
    }

    @Test fun permissionRevocationNeverReinterpretsVirtualCoordinatesAsMain() {
        val route = VirtualScreenRoutingPolicy(true)
        assertTrue(route.shouldRoute("tap", enabled = false))
        assertEquals(VirtualScreenRoutingPolicy.Target.VIRTUAL_SCREEN, route.target)
    }

    @Test fun releaseAndTakeoverInvalidateObservationsButPreserveSelectedTarget() {
        val route = VirtualScreenRoutingPolicy()
        route.selectCanvas(31)
        val observedRevision = route.revision
        route.invalidate()
        assertTrue(route.revision > observedRevision)
        assertEquals(VirtualScreenRoutingPolicy.Target.CANVAS, route.target)
        assertEquals(31, route.displayId)
    }

    @Test fun explicitMainOverridesOwnRetainedScreenAndClearsFallbackApproval() {
        val route = VirtualScreenRoutingPolicy(true)
        route.approvePrimary()
        assertTrue(route.usesPrimary)
        route.selectMain()
        assertFalse(route.usesPrimary)
        assertFalse(route.shouldRoute("tap", true))
    }

    @Test fun directScreenObservationAndOrdinaryToolsUseOneTarget() {
        val route = VirtualScreenRoutingPolicy()
        route.selectVirtual() // successful virtual_screen(action=observe/create/launch)
        assertTrue(route.shouldRoute("observe_screen", true))
        assertTrue(route.shouldRoute("tap", true))
        route.selectMain() // successful present or explicit main launch
        assertFalse(route.shouldRoute("tap", true))
    }
}
