package io.github.mangi.eta.ui.components.coui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.CouiDialogDefaults
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.app.LocalResolvedDarkTheme

/** Legacy custom message editors retain their action/selection owners but use the same
 * COUI surface, continuous corner shape and theme resolution as native modal pages.
 * MIUIX deliberately retains its existing geometry and custom-dialog color.
 */
@Composable
internal fun messageDialogShape(): Shape = if (LocalUiStyle.current == AppearanceUiStyle.COUI)
    CouiDialogDefaults.shape else RoundedCornerShape(24.dp)

@Composable
internal fun messageDialogSurface(): Color = if (LocalUiStyle.current == AppearanceUiStyle.COUI)
    CouiTheme.colorScheme.surface
    else if (LocalResolvedDarkTheme.current) Color(0xFF242428) else Color(0xFFF4F4F7)

@Composable
internal fun messageDialogInk(): Color = if (LocalUiStyle.current == AppearanceUiStyle.COUI)
    CouiTheme.colorScheme.onSurface
    else if (LocalResolvedDarkTheme.current) Color(0xFFF0F0F0) else Color(0xFF202020)
