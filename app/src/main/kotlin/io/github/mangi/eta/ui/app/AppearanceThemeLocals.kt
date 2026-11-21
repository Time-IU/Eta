package io.github.mangi.eta.ui.app

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Density
import io.github.mangi.eta.data.model.AppearanceSettings
import io.github.mangi.eta.data.model.AppearanceTopBarBlurStyle
import io.github.mangi.eta.data.model.AppearanceUiStyle

internal val LocalAppearanceSettings = staticCompositionLocalOf { AppearanceSettings() }
internal val LocalUiStyle = staticCompositionLocalOf { AppearanceUiStyle.COUI }
internal val LocalBlurEnabled = staticCompositionLocalOf { true }
internal val LocalTopBarBlurStyle = staticCompositionLocalOf { AppearanceTopBarBlurStyle.GAUSSIAN }
internal val LocalPlatformDensity = staticCompositionLocalOf<Density?> { null }

/** Actual application theme, including explicit light/dark overrides. */
internal val LocalResolvedDarkTheme = staticCompositionLocalOf { false }
/** Disabled for system/service overlays that can temporarily use a software Canvas. */
internal val LocalLiquidGlassAllowed = staticCompositionLocalOf { false }
