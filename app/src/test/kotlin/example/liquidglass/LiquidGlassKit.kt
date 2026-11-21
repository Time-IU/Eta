// Reusable host adapter, not an upstream high-level component.
// Dependency: io.github.kyant0:backdrop-android:2.0.1 (Apache-2.0).
// Change this package before use. Keep original Backdrop license in the host project.
package example.liquidglass

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
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.isRuntimeShaderSupported

enum class GlassThemeMode {
    SYSTEM, LIGHT, DARK;
    fun resolve(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}

private data class GlassEnvironment(val dark: Boolean = false, val enabled: Boolean = false,
    val accent: Color = Color(0xFF638DEA), val backdrop: Backdrop? = null)
private val LocalGlass = staticCompositionLocalOf { GlassEnvironment() }

private fun DrawScope.lightField(dark: Boolean, accent: Color) {
    drawRect(if (dark) Color(0xFF151C2C) else Color(0xFFE7EFFB))
    drawRect(Brush.linearGradient(listOf(accent.copy(alpha = 0.30f),
        Color(0xFF9B8CE8).copy(alpha = 0.23f), Color(0xFF68D8CF).copy(alpha = 0.25f)),
        Offset.Zero, Offset(size.width, size.height)))
    drawCircle(Color.White.copy(alpha = if (dark) 0.07f else 0.40f),
        size.minDimension * 0.65f, Offset(size.width * 0.14f, size.height * 0.22f))
}

/** Decorative source, not actual conversation/wallpaper capture. Stable content identity. */
@Composable
fun ProvideLiquidGlass(dark: Boolean, enabled: Boolean, accent: Color, content: @Composable () -> Unit) {
    val source = rememberLayerBackdrop()
    val active = enabled && isRuntimeShaderSupported() && LocalView.current.isHardwareAccelerated
    Box(Modifier.fillMaxSize()) {
        if (active) Box(Modifier.fillMaxSize().layerBackdrop(source).drawBehind { lightField(dark, accent) })
        CompositionLocalProvider(LocalGlass provides GlassEnvironment(dark, active, accent,
            if (active) source else null)) { content() }
    }
}

/** Supply opaque fallback and retain host text colors/semantics. */
@Composable
fun Modifier.liquidGlass(shape: Shape, fallback: Color, independentWindow: Boolean = false): Modifier {
    val env = LocalGlass.current
    val canvas = rememberCanvasBackdrop { lightField(env.dark, env.accent) }
    if (!env.enabled || !isRuntimeShaderSupported() || !LocalView.current.isHardwareAccelerated)
        return clip(shape).background(fallback)
    val source = if (independentWindow) canvas else env.backdrop ?: canvas
    val tint = if (env.dark) Color(0xFF11151E).copy(alpha = 0.66f) else Color.White.copy(alpha = 0.68f)
    return drawBackdrop(backdrop = source, shape = { shape }, effects = {
        vibrancy(); blur(8.dp.toPx()); lens(12.dp.toPx(), 24.dp.toPx())
    }, onDrawSurface = { drawRect(tint) })
        .border(0.7.dp, Color.White.copy(alpha = if (env.dark) 0.18f else 0.78f), shape)
}
