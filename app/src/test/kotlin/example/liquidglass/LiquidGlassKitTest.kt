package example.liquidglass

import org.junit.Assert.*
import org.junit.Test

/** Compiles the exact reusable Skill template alongside these assertions. */
class LiquidGlassKitTest {
    @Test fun systemFollowsEverySystemState() {
        for (dark in listOf(false, true)) assertEquals(dark, GlassThemeMode.SYSTEM.resolve(dark))
    }
    @Test fun lightIsAlwaysLight() {
        for (dark in listOf(false, true)) assertFalse(GlassThemeMode.LIGHT.resolve(dark))
    }
    @Test fun darkIsAlwaysDark() {
        for (dark in listOf(false, true)) assertTrue(GlassThemeMode.DARK.resolve(dark))
    }
}
