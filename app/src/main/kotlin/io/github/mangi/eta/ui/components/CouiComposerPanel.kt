package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.Dialog
import com.itos.coui.dialog.CouiDialogPlacement
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.components.coui.couiPrimaryDialogAction
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial

/** Same native COUI shape, real host-window material, dim/entrance and footer as settings
 * dialogs. The host backdrop is obtained BEFORE opening the dialog; never sample itself.
 * Do not apply the decorative cross-window LiquidGlass source to COUI modal content.
 * The caller retains its own bounded body/scroll state; the close action is outside it.
 */
@Composable
internal fun CouiComposerPanel(
    title: String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val backdrop = rememberCouiWindowBackdrop()
    val material = rememberCouiDialogMaterial()
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    Dialog(
        onDismissRequest = onDismiss,
        dark = CouiTheme.isDark,
        title = title,
        placement = CouiDialogPlacement.Bottom,
        confirmAction = couiPrimaryDialogAction(stringResource(R.string.action_close), onDismiss),
        backdrop = backdrop,
        material = material,
        visible = true,
    ) {
        Column(Modifier.fillMaxWidth().heightIn(max = (height * 0.65f).coerceAtLeast(1.dp)), content = content)
    }
}
