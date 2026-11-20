package io.github.mangi.eta.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** One measured row, not two arbitrary half-width columns. Normal text is 14sp in both
 * themes; narrower windows fit the combined label/value before ever needing overflow.
 * At extreme accessibility sizes, horizontal scrolling preserves all text, never ellipses.
 * System font scaling remains enabled; the help action retains its full 32dp target.
 */
@Composable
internal fun CompactMetricRow(label: String, value: String, onHelp: () -> Unit) {
    val ink = if (LocalUiStyle.current == AppearanceUiStyle.COUI) CouiTheme.colorScheme.onSurface
        else MiuixTheme.colorScheme.onSurface
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val baseStyle = MiuixTheme.textStyles.body1.copy(fontSize = 14.sp, lineHeight = 20.sp)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        val naturalWidth = measurer.measure(label, baseStyle, softWrap = false, maxLines = 1).size.width +
            measurer.measure(value, baseStyle, softWrap = false, maxLines = 1).size.width
        val available = with(density) { (maxWidth - 44.dp).toPx() }.coerceAtLeast(1f)
        val fontSize = compactMetricFontSize(available, naturalWidth.toFloat())
        val style = baseStyle.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 20f / 14f).sp)
        val fittedTextWidth = measurer.measure(label, style, softWrap = false, maxLines = 1).size.width +
            measurer.measure(value, style, softWrap = false, maxLines = 1).size.width
        val contentWidth = maxOf(maxWidth, with(density) { fittedTextWidth.toDp() } + 44.dp)
        Row(Modifier.horizontalScroll(rememberScrollState()).width(contentWidth),
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = style, color = ink.copy(alpha = 0.64f), maxLines = 1, softWrap = false)
            Box(Modifier.size(32.dp).clickable(role = Role.Button,
                onClickLabel = stringResource(R.string.refine_usage_explain), onClick = onHelp),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.HelpOutline, stringResource(R.string.refine_usage_explain),
                    modifier = Modifier.size(17.dp), tint = ink.copy(alpha = 0.48f))
            }
            Spacer(Modifier.widthIn(min = 12.dp).weight(1f))
            Text(value, style = style, color = ink, maxLines = 1, softWrap = false)
        }
    }
}

/** Layout-only fitting; numbers, precision and metric meaning are never changed. */
internal fun compactMetricFontSize(availablePx: Float, naturalTextPx: Float): Float =
    if (naturalTextPx <= 0f || naturalTextPx <= availablePx) 14f
    else (14f * availablePx / naturalTextPx).coerceIn(10f, 14f)
