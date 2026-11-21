package io.github.mangi.eta.ui.components

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.itos.coui.dialog.Dialog as CouiDialog
import com.itos.coui.dialog.CouiDialogAction
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import top.yukonga.miuix.kmp.basic.Text
import io.github.mangi.eta.R
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.ui.components.coui.couiPrimaryDialogAction
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.menu.rememberCouiWindowBackdrop
import com.itos.coui.switch.CouiSwitchDefaults
import com.itos.coui.switch.Switch as CouiSwitch
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.components.coui.CouiButton
import io.github.mangi.eta.ui.components.coui.CouiButtonRole
import io.github.mangi.eta.ui.components.coui.CouiIcon
import io.github.mangi.eta.ui.components.coui.rememberCouiDialogMaterial
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextButtonColors
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.overlay.OverlayDialog

/** Compatibility names for incoming pages, not a replacement global theme.
 * Existing EtaPreference/EtaSwitchPreference/selection rows remain their sole owners.
 */
internal object EtaPreferenceColors {
    val Blue = Color(0xFF0080FF)
    val Green = StatusSuccess
    val Orange = Color(0xFFFF7700)
    val Yellow = StatusWarning
}

internal object EtaPreferenceDefaults {
    val SidePadding = 16.dp
    val GroupSpacing = 16.dp
    val IconSize = 24.dp
    val IconTextGap = 16.dp
    val ContentStart = SidePadding + IconSize + IconTextGap
    val RowMinHeight = 52.dp
    fun contentStart(hasLeading: Boolean) = if (hasLeading) ContentStart else SidePadding
}

@Composable
internal fun EtaPreferenceTheme(content: @Composable () -> Unit) {
    // The application already installs the selected COUI/MIUIX theme. Do not
    // reinstate incoming's hardcoded background/Monet overrides here.
    content()
}

@Composable
internal fun EtaTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp = 22.dp,
    minWidth: Dp = ButtonDefaults.MinWidth,
    minHeight: Dp = 44.dp,
    colors: TextButtonColors = ButtonDefaults.textButtonColors(),
    insideMargin: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
    textStyle: TextStyle? = null,
    interactionSource: MutableInteractionSource? = null,
    indication: Indication? = LocalIndication.current,
) {
    val accessibleModifier = modifier.sizeIn(minWidth = minWidth, minHeight = minHeight)
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        val primaryColors = ButtonDefaults.textButtonColorsPrimary()
        val role = when {
            colors.color == MiuixTheme.colorScheme.error -> CouiButtonRole.DestructiveFilled
            colors.textColor == MiuixTheme.colorScheme.error -> CouiButtonRole.Destructive
            colors.color == primaryColors.color -> CouiButtonRole.Primary
            else -> CouiButtonRole.Secondary
        }
        CouiButton(
            text = text, onClick = onClick, modifier = accessibleModifier,
            enabled = enabled, role = role, minHeight = minHeight,
            contentPadding = insideMargin, maxLines = Int.MAX_VALUE, textStyle = textStyle,
        )
    } else {
        TextButton(
            text = text, onClick = onClick, modifier = accessibleModifier, enabled = enabled,
            cornerRadius = cornerRadius, minWidth = minWidth, minHeight = minHeight,
            colors = colors, insideMargin = insideMargin, textStyle = textStyle,
            interactionSource = interactionSource, indication = indication,
        )
    }
}

@Composable
internal fun EtaSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    // Do not shrink the native switch or its semantics to incoming's 44x24 dp.
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        val dark = CouiTheme.isDark
        CouiSwitch(
            checked = checked, onCheckedChange = onCheckedChange,
            modifier = modifier, enabled = enabled, dark = dark,
            colors = CouiSwitchDefaults.colors(CouiPageDefaults.themeColors(dark).accent, dark),
        )
    } else {
        Switch(checked = checked, onCheckedChange = onCheckedChange,
            modifier = modifier, enabled = enabled)
    }
}

@Composable
internal fun EtaCard(
    modifier: Modifier = Modifier,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(),
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    showIndication: Boolean = false,
    holdDownState: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        val interaction = if (onClick != null || onLongPress != null) {
            Modifier.combinedClickable(role = Role.Button, onLongClick = onLongPress,
                onClick = { onClick?.invoke() })
        } else Modifier
        Column(
            modifier = modifier.clip(com.itos.coui.shape.CouiShape(24.dp, 3f))
                .background(CouiPageDefaults.themeColors().card)
                .then(interaction).padding(insideMargin),
            content = content,
        )
    } else {
        // One signature avoids the two all-default overloads from the old shim.
        Card(modifier = modifier, insideMargin = insideMargin, colors = colors,
            pressFeedbackType = pressFeedbackType, showIndication = showIndication,
            holdDownState = holdDownState, onClick = onClick, onLongPress = onLongPress,
            content = content)
    }
}

@Composable
internal fun EtaPreferenceGroup(
    modifier: Modifier = Modifier.padding(horizontal = EtaPreferenceDefaults.SidePadding)
        .padding(bottom = EtaPreferenceDefaults.GroupSpacing),
    insideMargin: PaddingValues = PaddingValues(0.dp),
    colors: CardColors = CardDefaults.defaultColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    EtaCard(modifier = modifier, insideMargin = insideMargin, colors = colors, content = content)
}

@Composable
internal fun EtaPreferenceGroupTitle(text: String, modifier: Modifier = Modifier) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiText(text = text, kind = CouiTextKind.GroupTitle,
            modifier = modifier.padding(horizontal = 32.dp, vertical = 8.dp).semantics { heading() })
    } else {
        SmallTitle(text = text, modifier = modifier.semantics { heading() })
    }
}

@Composable
internal fun EtaPreferenceIcon(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.onBackground,
    enabled: Boolean = true,
) {
    Box(modifier.size(EtaPreferenceDefaults.IconSize), contentAlignment = Alignment.Center) {
        val color = if (enabled) tint else tint.copy(alpha = 0.38f)
        if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
            CouiIcon(imageVector = icon, contentDescription = null,
                modifier = Modifier.size(EtaPreferenceDefaults.IconSize), tint = color)
        } else {
            Icon(imageVector = icon, contentDescription = null,
                modifier = Modifier.size(EtaPreferenceDefaults.IconSize), tint = color)
        }
    }
}

@Composable
internal fun EtaPreferenceDivider(hasLeading: Boolean = true, modifier: Modifier = Modifier) {
    val color = if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiPageDefaults.themeColors().summary.copy(alpha = 0.15f)
    } else MiuixTheme.colorScheme.onBackground.copy(alpha = 0.1f)
    HorizontalDivider(
        modifier = modifier.padding(start = EtaPreferenceDefaults.contentStart(hasLeading),
            end = EtaPreferenceDefaults.SidePadding),
        thickness = 0.5.dp, color = color,
    )
}

@Composable
internal fun EtaWindowDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    modifier: Modifier = Modifier,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    cornerRadius: Dp = 24.dp,
    content: @Composable () -> Unit,
) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        val finished by rememberUpdatedState(onDismissFinished)
        var hasBeenShown by remember { mutableStateOf(false) }
        // COUI exposes no animation-finished callback: notify on a logical
        // shown -> hidden transition, never on an initially hidden dialog.
        LaunchedEffect(show) {
            if (!show && hasBeenShown) finished?.invoke()
            hasBeenShown = show
        }
        CouiDialog(
            visible = show,
            onDismissRequest = { onDismissRequest?.invoke() },
            dark = CouiTheme.isDark,
            title = title,
            message = summary,
            modifier = modifier,
            // Native COUI Dialog reserves this action outside its scrollable
            // body. Content may also supply save/cancel actions; never infer
            // destructive operations or fabricate their callbacks here.
            confirmAction = couiPrimaryDialogAction(
                text = stringResource(R.string.action_close),
                onClick = { onDismissRequest?.invoke() },
                enabled = onDismissRequest != null,
            ),
            material = rememberCouiDialogMaterial(),
            backdrop = rememberCouiWindowBackdrop(),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                PorDialogContent(content)
            }
        }
    } else {
        WindowDialog(show = show, title = title, summary = summary,
            modifier = modifier, onDismissRequest = onDismissRequest,
            onDismissFinished = onDismissFinished, cornerRadius = cornerRadius) {
            PorDialogContent(content)
        }
    }
}

@Composable
internal fun EtaOverlayDialog(
    show: Boolean,
    title: String? = null,
    summary: String? = null,
    modifier: Modifier = Modifier,
    onDismissRequest: (() -> Unit)? = null,
    onDismissFinished: (() -> Unit)? = null,
    cornerRadius: Dp = 24.dp,
    content: @Composable () -> Unit,
) {
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        EtaWindowDialog(show, title, summary, modifier, onDismissRequest, onDismissFinished,
            cornerRadius = cornerRadius, content = content)
    } else {
        OverlayDialog(show = show, title = title, summary = summary,
            modifier = modifier, onDismissRequest = onDismissRequest,
            onDismissFinished = onDismissFinished, cornerRadius = cornerRadius) {
            PorDialogContent(content)
        }
    }
}

/** Measure trailing actions first, then give scrollable leading content only the
 * remaining height. Direct footer buttons never compete with a 420/460dp body.
 * Existing content keeps its own scroll state; no nested verticalScroll is added.
 */
@Composable
private fun PorDialogContent(content: @Composable () -> Unit) {
    val availableHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.7f
    Layout(content = content, modifier = Modifier.fillMaxWidth().heightIn(max = availableHeight)) { children, constraints ->
        var remaining = constraints.maxHeight
        val measured = arrayOfNulls<androidx.compose.ui.layout.Placeable>(children.size)
        for (index in children.indices.reversed()) {
            val child = children[index].measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = remaining))
            measured[index] = child
            remaining = (remaining - child.height).coerceAtLeast(0)
        }
        val height = measured.sumOf { it?.height ?: 0 }.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(constraints.maxWidth, height) {
            var y = 0
            measured.forEach { child ->
                child?.placeRelative(0, y)
                y += child?.height ?: 0
            }
        }
    }
}

/**
 * Read-only content has exactly one footer: close and copy the original full text.
 * This is deliberately separate from EtaWindowDialog: editors/confirmation pages
 * retain their existing save/cancel/default-close semantics.
 *
 * [content] must NOT add verticalScroll/heightIn or footer actions. COUI owns the
 * native scroll panel and native action bar; MIUIX uses explicit body/footer slots.
 * Both receive the real window constraints (including the native shell's insets),
 * rather than a fixed 400/460dp viewport or an extra fraction of screen height.
 */
@Composable
internal fun EtaReadOnlyDialog(
    title: String,
    summary: String? = null,
    copyAllText: String = stringResource(R.string.selection_copy_all),
    onDismissRequest: () -> Unit,
    onCopyAll: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val closeText = stringResource(R.string.action_close)
    if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        // Use the library's real buttonPanel, NOT an extra action row inside its
        // scrolling content. Its title/summary follow native COUI scrolling policy.
        CouiDialog(
            visible = true,
            title = title,
            message = summary,
            onDismissRequest = onDismissRequest,
            dark = CouiTheme.isDark,
            confirmAction = couiPrimaryDialogAction(
                text = copyAllText,
                onClick = onCopyAll,
            ),
            dismissAction = CouiDialogAction(
                text = closeText,
                onClick = onDismissRequest,
            ),
            material = rememberCouiDialogMaterial(),
            backdrop = rememberCouiWindowBackdrop(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    .padding(bottom = 12.dp),
                content = content,
            )
        }
    } else {
        WindowDialog(
            show = true,
            title = title,
            summary = summary,
            onDismissRequest = onDismissRequest,
        ) {
            ReadOnlyDialogContent(
                closeText = closeText,
                copyAllText = copyAllText,
                onDismissRequest = onDismissRequest,
                onCopyAll = onCopyAll,
                content = content,
            )
        }
    }
}

/** The copy source is the exact input, never a preview, trimmed or segmented body. */
@Composable
internal fun EtaReadOnlyTextDialog(
    title: String,
    text: String,
    summary: String? = null,
    copyAllText: String = stringResource(R.string.selection_copy_all),
    onDismissRequest: () -> Unit,
    onCopyAll: (() -> Unit)? = null,
) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    EtaReadOnlyDialog(
        title = title,
        summary = summary,
        copyAllText = copyAllText,
        onDismissRequest = onDismissRequest,
        onCopyAll = {
            if (onCopyAll != null) onCopyAll() else clipboard.setText(AnnotatedString(text))
        },
    ) {
        SelectionContainer {
            Text(
                text = text,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
