package io.github.mangi.eta.ui.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UnifiedEffectsPolicyTest {
    @Test fun allEightCombinationsRespectSavedPreferencePlatformAndHost() {
        for (requested in listOf(false, true)) for (supported in listOf(false, true))
            for (safeHost in listOf(false, true)) {
                assertEquals("$requested / $supported / $safeHost", requested && supported && safeHost,
                    unifiedEffectsEnabled(requested, supported, safeHost))
            }
    }
    @Test fun disableDoesNotBecomeAnImplicitCouiOverride() {
        assertEquals(false, unifiedEffectsEnabled(false, true, true))
        assertEquals(true, unifiedEffectsEnabled(true, true, true))
    }
    @Test fun serviceOverlayNeverRunsHardwareEffects() {
        assertEquals(false, unifiedEffectsEnabled(true, true, false))
    }
}
