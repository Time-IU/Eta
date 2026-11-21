package io.github.mangi.eta.data.model

import io.github.mangi.eta.ui.components.glass.glassEffectsEnabled
import org.junit.Assert.*
import org.junit.Test

class LiquidGlassThemeTest {
    @Test fun systemThemeTracksBothSystemStates() {
        assertFalse(AppearanceThemeMode.SYSTEM.resolveDark(false))
        assertTrue(AppearanceThemeMode.SYSTEM.resolveDark(true))
    }
    @Test fun explicitLightIsIndependentOfSystem() {
        assertFalse(AppearanceThemeMode.LIGHT.resolveDark(false))
        assertFalse(AppearanceThemeMode.LIGHT.resolveDark(true))
    }
    @Test fun explicitDarkIsIndependentOfSystem() {
        assertTrue(AppearanceThemeMode.DARK.resolveDark(false))
        assertTrue(AppearanceThemeMode.DARK.resolveDark(true))
    }
    @Test fun allPersistedThemeModesRetainTheirMeaning() {
        AppearanceThemeMode.entries.forEach { mode ->
            assertEquals(mode, AppearanceThemeMode.fromPersistedValue(mode.persistedValue))
        }
        assertEquals(AppearanceThemeMode.SYSTEM, AppearanceThemeMode.fromPersistedValue(null))
    }
    @Test fun effectsRequireAllFourSafetyConditions() {
        for (mask in 0..15) {
            assertEquals(mask == 15, glassEffectsEnabled(mask and 1 != 0, mask and 2 != 0,
                mask and 4 != 0, mask and 8 != 0))
        }
    }
    @Test fun disablingGlassNeverChangesThemeOrUiStyle() {
        for (style in AppearanceUiStyle.entries) for (mode in AppearanceThemeMode.entries) {
            val before = AppearanceSettings(uiStyle = style, themeMode = mode)
            val after = before.copy(liquidGlassEnabled = false).normalized()
            assertFalse(after.liquidGlassEnabled)
            assertEquals(style, after.uiStyle)
            assertEquals(mode, after.themeMode)
            assertTrue(after.blurEnabled)
        }
    }
}
