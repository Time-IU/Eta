package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import top.yukonga.miuix.kmp.basic.TextButton

/** New UI-core actions use the existing native button family, not deleted legacy EtaControls. */
@Composable
internal fun UiCoreTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    when (LocalUiStyle.current) {
        AppearanceUiStyle.COUI -> CouiButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            minHeight = 36.dp,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        )
        AppearanceUiStyle.MIUIX -> TextButton(
            text = text,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            minHeight = 36.dp,
            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
