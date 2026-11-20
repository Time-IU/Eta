package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared opaque, rounded composer surface; bounded body and an independent close footer. */
@Composable
internal fun ComposerPanelDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    showClose: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!show) return
    val height = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val surface = if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        if (CouiTheme.isDark) Color(0xFF24262B) else Color(0xFFF4F4F7)
    } else MiuixTheme.colorScheme.surfaceContainer
    val ink = if (LocalUiStyle.current == AppearanceUiStyle.COUI) CouiTheme.colorScheme.onSurface
        else MiuixTheme.colorScheme.onSurface
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
                .widthIn(max = 560.dp).fillMaxWidth()
                .heightIn(max = (height - 48.dp).coerceAtLeast(1.dp))
                .clip(RoundedCornerShape(24.dp)).background(surface),
        ) {
            if (title != null) {
                Text(title, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    color = ink, fontSize = 16.sp, lineHeight = 22.sp)
            }
            Column(Modifier.weight(1f, fill = false).heightIn(max = height * 0.70f), content = content)
            if (showClose) EtaTextButton(stringResource(R.string.action_close), onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}
