package io.github.mangi.eta.agent.display

import org.junit.Assert.*
import org.junit.Test

class UnifiedCanvasLogicTest {
    @Test fun rootAndHookTransportsAreMutuallyExclusiveUnderOneSlot() {
        val slot = UnifiedCanvasSlot()
        assertTrue(slot.attach("owner", "session", UnifiedCanvasTransportPolicy.Kind.HOOK_IN_PROCESS))
        assertFalse(slot.attach("owner", "session", UnifiedCanvasTransportPolicy.Kind.ROOT_PROCESS))
        assertFalse(slot.attach("other", "root", UnifiedCanvasTransportPolicy.Kind.ROOT_PROCESS))
        assertTrue(slot.activate("owner", "session", 34))
        assertEquals(UnifiedCanvasTransportPolicy.Kind.HOOK_IN_PROCESS, slot.resolve("owner")?.transport)
        assertTrue(slot.detach("owner", "session"))
        assertTrue(slot.attach("other", "root", UnifiedCanvasTransportPolicy.Kind.ROOT_PROCESS))
        assertFalse(slot.detach("owner", "session"))
    }
    @Test fun hookOnlyIsARealSelectableTransportWithoutGrantingRootToOtherTools() {
        assertEquals(UnifiedCanvasTransportPolicy.Kind.HOOK_IN_PROCESS, UnifiedCanvasTransportPolicy.choose(false, true))
        assertEquals(UnifiedCanvasTransportPolicy.Kind.ROOT_PROCESS, UnifiedCanvasTransportPolicy.choose(true, true))
        assertEquals(UnifiedCanvasTransportPolicy.Kind.UNAVAILABLE, UnifiedCanvasTransportPolicy.choose(false, false))
        assertTrue(UnifiedCanvasTransportPolicy.bypassRootRequirement("virtual_screen", true, false))
        assertTrue(UnifiedCanvasTransportPolicy.bypassRootRequirement("lingscreen_open_app", true, false))
        assertTrue(UnifiedCanvasTransportPolicy.bypassRootRequirement("tap", true, true))
        assertFalse(UnifiedCanvasTransportPolicy.bypassRootRequirement("terminal", true, true))
        assertFalse(UnifiedCanvasTransportPolicy.bypassRootRequirement("wifi_credentials", true, true))
        assertFalse(UnifiedCanvasTransportPolicy.bypassRootRequirement("tap", true, false))
        assertFalse(UnifiedCanvasTransportPolicy.bypassRootRequirement("virtual_screen", false, true))
    }

    @Test fun everyLifecycleSettingDispatchesToTheSameEngine() {
        val old = UnifiedCanvasSettingsSignal(true, false, true, 20)
        assertFalse(old.requiresDispatch(old))
        assertTrue(old.copy(enabled = false).requiresDispatch(old))
        assertTrue(old.copy(screenOff = true).requiresDispatch(old))
        assertTrue(old.copy(keepScreenOn = false).requiresDispatch(old))
        assertTrue(old.copy(idleMinutes = 5).requiresDispatch(old))
        assertFalse(old.requiresRecreate(false))
        assertTrue(old.copy(screenOff = true).requiresRecreate(false))
        assertTrue(old.requiresRecreate(true))
    }

    @Test fun bothEntrancesResolveTheSameActivatedDisplay() {
        val engine = UnifiedCanvasSlot()
        assertTrue(engine.attach("owner", "session"))
        assertTrue(engine.activate("owner", "session", 42))
        val ordinaryLaunch = engine.resolve("owner")
        // Legacy alias cannot allocate another backend/session for the same run.
        assertTrue(engine.attach("owner", "session"))
        val legacyLaunch = engine.resolve("owner")
        assertEquals(ordinaryLaunch, legacyLaunch)
        assertEquals(42, legacyLaunch?.displayId)
        assertFalse(engine.attach("owner", "parallel-session"))
    }
    @Test fun otherOwnerCannotStealSessionOrDisplay() {
        val engine = UnifiedCanvasSlot()
        assertTrue(engine.attach("a", "a1"))
        assertFalse(engine.permits("b"))
        assertFalse(engine.attach("b", "b1"))
        assertFalse(engine.activate("b", "a1", 8))
        assertNull(engine.resolve("b"))
    }
    @Test fun delayedReleaseCannotCloseReplacement() {
        val engine = UnifiedCanvasSlot()
        assertTrue(engine.attach("a", "old"))
        assertTrue(engine.detach("a", "old"))
        assertTrue(engine.attach("a", "new"))
        assertFalse(engine.detach("a", "old"))
        assertEquals("new", engine.resolve("a")?.id)
    }
    @Test fun settingsNeverOverrideAnExplicitFalseWithLegacyTrue() {
        assertFalse(UnifiedCanvasSettingsPolicy.enabled(false, true))
        assertTrue(UnifiedCanvasSettingsPolicy.enabled(true, false))
        assertFalse(UnifiedCanvasSettingsPolicy.background(false, true))
    }
    @Test fun missingSettingsFollowLegacySelectionWithoutOR() {
        assertTrue(UnifiedCanvasSettingsPolicy.enabled(null, true))
        assertFalse(UnifiedCanvasSettingsPolicy.enabled(null, false))
        assertFalse(UnifiedCanvasSettingsPolicy.background(null, false))
    }
    @Test fun singleRunLeaseBlocksSecondRunAndLateCancellation() {
        val lease = VirtualScreenRunLease("run-a")
        assertFalse(lease.acquire("run-b"))
        assertTrue(lease.release("run-a", true))
        assertTrue(lease.acquire("run-b"))
        assertFalse(lease.release("run-a", false))
        assertFalse(lease.retireIdle())
        assertTrue(lease.release("run-b", false))
        assertFalse(lease.acquire("run-c"))
    }
    @Test fun invalidationAndDisabledSettingCannotMigrateOldCoordinatesToMain() {
        val routing = VirtualScreenRoutingPolicy()
        routing.selectVirtual()
        val observationRevision = routing.revision
        routing.invalidate()
        assertTrue(routing.revision > observationRevision)
        assertEquals(VirtualScreenRoutingPolicy.Target.VIRTUAL_SCREEN, routing.target)
        assertTrue(routing.shouldRoute("tap", false))
        assertFalse(routing.usesPrimary)
    }
    @Test fun onlyExplicitApprovalAuthorizesFallbackAndChangesRevision() {
        val routing = VirtualScreenRoutingPolicy(true)
        val oldRevision = routing.revision
        assertFalse(routing.usesPrimary)
        routing.approvePrimary()
        assertTrue(routing.usesPrimary)
        assertEquals(VirtualScreenRoutingPolicy.Target.MAIN, routing.target)
        assertTrue(routing.revision > oldRevision)
        assertFalse(routing.shouldRoute("tap", true))
    }
    @Test fun releasedSlotAcceptsNextOwnerNotAnOldActivation() {
        val engine = UnifiedCanvasSlot()
        engine.attach("a", "one")
        engine.detach("a", "one")
        engine.attach("b", "two")
        assertFalse(engine.activate("a", "one", 42))
        assertTrue(engine.activate("b", "two", 43))
        assertEquals(43, engine.resolve("b")?.displayId)
    }
}
