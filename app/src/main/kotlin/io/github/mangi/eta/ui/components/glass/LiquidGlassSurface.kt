package io.github.mangi.eta.ui.components.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.isRuntimeShaderSupported
import io.github.mangi.eta.ui.app.LocalAppearanceSettings
import io.github.mangi.eta.ui.app.LocalLiquidGlassAllowed
import io.github.mangi.eta.ui.app.LocalResolvedDarkTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val LocalGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Static decorative light field, not a capture of conversations or system wallpaper. */
private fun DrawScope.glassLightField(dark: Boolean, accent: Color) {
    drawRect(if (dark) Color(0xFF151C2C) else Color(0xFFE7EFFB))
    drawRect(Brush.linearGradient(
        listOf(accent.copy(alpha = if (dark) 0.36f else 0.27f),
            Color(0xFF9B8CE8).copy(alpha = if (dark) 0.24f else 0.21f),
            Color(0xFF68D8CF).copy(alpha = if (dark) 0.28f else 0.23f)),
        start = Offset.Zero, end = Offset(size.width, size.height),
    ))
    drawCircle(Color.White.copy(alpha = if (dark) 0.07f else 0.40f),
        radius = size.minDimension * 0.65f,
        center = Offset(size.width * 0.14f, size.height * 0.22f))
    drawCircle(accent.copy(alpha = if (dark) 0.14f else 0.18f),
        radius = size.minDimension * 0.42f,
        center = Offset(size.width * 0.90f, size.height * 0.78f))
}

/** A single background source; consumers are siblings and can never sample themselves. */
@Composable
internal fun LiquidGlassScene(content: @Composable () -> Unit) {
    val active = liquidGlassActive()
    val backdrop = rememberLayerBackdrop()
    val dark = LocalResolvedDarkTheme.current
    val accent = MiuixTheme.colorScheme.primary
    Box(Modifier.fillMaxSize()) {
        if (active) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)
                .drawGlassField(dark, accent))
        }
        // Keep the content group at the same call site when glass is toggled.
        CompositionLocalProvider(LocalGlassBackdrop provides if (active) backdrop else null) { content() }
    }
}

private fun Modifier.drawGlassField(dark: Boolean, accent: Color): Modifier =
    drawBehind { glassLightField(dark, accent) }

internal fun glassEffectsEnabled(enabled: Boolean, blurEnabled: Boolean, shader: Boolean, hardware: Boolean): Boolean =
    enabled && blurEnabled && shader && hardware

@Composable
internal fun liquidGlassActive(): Boolean {
    val appearance = LocalAppearanceSettings.current
    return LocalLiquidGlassAllowed.current && glassEffectsEnabled(appearance.liquidGlassEnabled, appearance.blurEnabled,
        isRuntimeShaderSupported(), LocalView.current.isHardwareAccelerated)
}

/** Dialogs own a CanvasBackdrop; layer coordinates are never reused across windows. */
@Composable
internal fun Modifier.liquidGlassSurface(
    shape: Shape,
    independentWindow: Boolean = false,
    fallbackColor: Color = MiuixTheme.colorScheme.surfaceContainer,
): Modifier {
    val dark = LocalResolvedDarkTheme.current
    val accent = MiuixTheme.colorScheme.primary
    val localBackdrop = LocalGlassBackdrop.current
    val canvasBackdrop = rememberCanvasBackdrop { glassLightField(dark, accent) }
    if (!liquidGlassActive()) return clip(shape).background(fallbackColor)
    val source = if (independentWindow) canvasBackdrop else localBackdrop ?: canvasBackdrop
    val tint = if (dark) Color(0xFF11151E).copy(alpha = 0.66f)
        else Color.White.copy(alpha = 0.68f)
    return drawBackdrop(
        backdrop = source,
        shape = { shape },
        effects = {
            vibrancy()
            blur(8.dp.toPx())
            lens(12.dp.toPx(), 24.dp.toPx())
        },
        onDrawSurface = { drawRect(tint) },
    ).border(0.7.dp, if (dark) Color.White.copy(alpha = 0.18f)
        else Color.White.copy(alpha = 0.78f), shape)
}
