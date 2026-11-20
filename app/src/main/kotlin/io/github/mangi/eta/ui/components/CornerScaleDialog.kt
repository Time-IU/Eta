package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Composer actions share one surface rather than popup/dialog-specific backgrounds. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun CornerScaleDialog(
    show: Boolean, onDismiss: () -> Unit,
    widthFraction: Float = 0.92f, heightFraction: Float = 0.62f,
    wrapContent: Boolean = false, alignStart: Boolean = false,
    bottomPadding: Dp = CornerDialogBottomPadding,
    content: @Composable (requestClose: () -> Unit) -> Unit,
) {
    val h = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    ComposerPanelDialog(show, onDismiss) {
        Box(Modifier.fillMaxWidth().heightIn(max = h * heightFraction.coerceIn(0.1f, 0.7f))) {
            content(onDismiss)
        }
    }
}
internal val CornerDialogBottomPadding = 104.dp
internal val CornerDialogDefaultHeightFraction = 0.62f
