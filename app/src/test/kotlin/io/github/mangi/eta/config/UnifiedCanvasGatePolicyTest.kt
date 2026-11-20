package io.github.mangi.eta.config

import org.junit.Assert.*
import org.junit.Test

class UnifiedCanvasGatePolicyTest {
    @Test fun dataStorePresenceDoesNotOrTheTwoMasters() {
        val policy = io.github.mangi.eta.agent.display.UnifiedCanvasSettingsPolicy
        assertFalse(policy.enabled(null, false))
        assertFalse(policy.enabled(false, true))
        assertTrue(policy.enabled(true, false))
        assertTrue(policy.enabled(null, true))
        var effective: Boolean? = null
        UnifiedCanvasGatePolicy.mirror(policy.enabled(true, false), { effective = it; true }, { effective })
        assertTrue(UnifiedCanvasGatePolicy.enabled(effective, false))
    }
    @Test fun presenceWinsAndMissingSignalDenies() {
        assertTrue(UnifiedCanvasGatePolicy.enabled(true, false))
        assertFalse(UnifiedCanvasGatePolicy.enabled(false, true))
        assertFalse(UnifiedCanvasGatePolicy.enabled(null, false))
        assertTrue(UnifiedCanvasGatePolicy.enabled(null, true))
        assertFalse(UnifiedCanvasGatePolicy.enabled(null, null))
    }
    @Test fun explicitNewEnableDoesNotRewriteLegacyOrScreenOff() {
        val legacy = false
        val screenOff = false
        var remote: Boolean? = null
        UnifiedCanvasGatePolicy.mirror(true, { remote = it; true }, { remote })
        assertTrue(UnifiedCanvasGatePolicy.enabled(remote, legacy))
        assertFalse(legacy)
        assertFalse(screenOff)
        UnifiedCanvasGatePolicy.mirror(false, { remote = it; true }, { remote })
        assertFalse(UnifiedCanvasGatePolicy.enabled(remote, true))
    }
    @Test(expected = java.io.IOException::class) fun failedCommitIsNotAcknowledged() {
        UnifiedCanvasGatePolicy.mirror(true, { false }, { true })
    }
    @Test(expected = java.io.IOException::class) fun lostBridgeIsNotAcknowledged() {
        UnifiedCanvasGatePolicy.mirror(true, { true }, { null })
    }
    @Test(expected = java.io.IOException::class) fun mismatchedReadbackIsNotAcknowledged() {
        UnifiedCanvasGatePolicy.mirror(false, { true }, { true })
    }
    @Test(expected = SecurityException::class) fun binderErrorIsNotSwallowed() {
        UnifiedCanvasGatePolicy.mirror(true, { throw SecurityException("binder") }, { true })
    }
}
