package io.github.mangi.eta.ui.components

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.ui.app.LocalUiStyle
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Search stays in the chat; the bounded preview avoids pretending message-level scroll is exact. */
@Composable
internal fun ChatSearchToolbar(
    open: Boolean,
    query: String,
    hitIndex: Int,
    hitCount: Int,
    searching: Boolean,
    historyIncomplete: Boolean,
    historyLoading: Boolean,
    historyPaused: Boolean,
    historyFailed: Boolean,
    historyStalled: Boolean,
    loadedMessageCount: Int,
    onRetryHistory: () -> Unit,
    preview: ChatSearchPreview?,
    onOpen: () -> Unit,
    onQueryChange: (String) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onLocate: () -> Unit,
    onClose: () -> Unit,
) {
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val colors = MiuixTheme.colorScheme
    val couiColors = CouiPageDefaults.themeColors(CouiTheme.isDark)
    val foreground = if (isCoui) couiColors.title else colors.onSurface
    val accent = if (isCoui) couiColors.accent else colors.primary
    val surface = if (isCoui) {
        if (CouiTheme.isDark) androidx.compose.ui.graphics.Color(0xFF24262B) else androidx.compose.ui.graphics.Color.White
    } else colors.surfaceContainerHigh
    val shape = RoundedCornerShape(if (isCoui) 20.dp else 16.dp)
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    // Match the existing search field's deferred color update for movable theme content.
    var fieldColor by remember { mutableStateOf(foreground) }
    LaunchedEffect(foreground) { fieldColor = foreground }
    LaunchedEffect(open) {
        if (open) {
            withFrameNanos { }
            focusRequester.requestFocus()
        }
    }
    fun hideKeyboard() {
        keyboard?.hide()
        focusManager.clearFocus()
    }

    // Do not compose a BackHandler in service surfaces without a dispatcher owner.
    if (open && LocalOnBackPressedDispatcherOwner.current != null) {
        BackHandler {
            hideKeyboard()
            onClose()
        }
    }
    if (!open) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
            ChatSearchButton(Icons.Rounded.Search, stringResource(R.string.chat_search_open), true, onOpen)
        }
        return
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape).background(surface).border(0.5.dp, accent.copy(alpha = 0.25f), shape),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val placeholder = stringResource(R.string.chat_search_placeholder)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focusRequester)
                    .semantics { contentDescription = placeholder },
                textStyle = TextStyle(color = fieldColor, fontSize = 15.sp),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { hideKeyboard(); onLocate() }),
                decorationBox = { field ->
                    Box {
                        if (query.isEmpty()) Text(placeholder, color = foreground.copy(alpha = 0.55f), fontSize = 15.sp)
                        field()
                    }
                },
            )
            ChatSearchButton(Icons.Rounded.Close, stringResource(R.string.chat_search_close), true) {
                hideKeyboard()
                onClose()
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val status = when {
                query.isBlank() -> stringResource(R.string.chat_search_loaded_scope)
                searching -> stringResource(R.string.chat_search_searching)
                hitCount == 0 && historyIncomplete && (historyFailed || historyStalled) ->
                    stringResource(R.string.chat_search_incomplete)
                hitCount == 0 && historyIncomplete -> stringResource(R.string.chat_search_no_loaded_matches)
                hitCount == 0 -> stringResource(R.string.chat_search_no_matches)
                else -> stringResource(R.string.chat_search_count, hitIndex + 1, hitCount)
            }
            Text(status, color = foreground, fontSize = 13.sp,
                modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            ChatSearchButton(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.chat_search_previous), hitCount > 0) {
                hideKeyboard(); onPrevious()
            }
            ChatSearchButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.chat_search_next), hitCount > 0) {
                hideKeyboard(); onNext()
            }
        }
        if (query.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val scope = when {
                    !historyIncomplete -> stringResource(R.string.chat_search_scope_complete, loadedMessageCount)
                    historyLoading -> stringResource(R.string.chat_search_scope_loading, loadedMessageCount)
                    historyFailed -> stringResource(R.string.chat_search_scope_failed, loadedMessageCount)
                    historyStalled -> stringResource(R.string.chat_search_scope_stalled, loadedMessageCount)
                    historyPaused -> stringResource(R.string.chat_search_scope_paused, loadedMessageCount)
                    else -> stringResource(R.string.chat_search_scope_loading, loadedMessageCount)
                }
                Text(scope, fontSize = 12.sp, color = foreground.copy(alpha = 0.7f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (historyIncomplete && (historyFailed || historyStalled)) {
                    Text(stringResource(R.string.chat_search_retry), color = accent, fontSize = 13.sp,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = !historyLoading && !historyPaused, role = Role.Button) { onRetryHistory() }
                            .padding(horizontal = 10.dp, vertical = 12.dp))
                }
            }
        }
        if (preview != null) {
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { hideKeyboard(); onLocate() }
                .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                Text(stringResource(R.string.chat_search_locate), fontSize = 12.sp, color = accent)
                // Leading context has its own line budget. Even at large accessibility font
                // scales it cannot push the actual highlighted match out of the preview.
                if (preview.before.isNotEmpty()) {
                    Text(preview.before, color = foreground.copy(alpha = 0.7f), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(background = accent.copy(alpha = 0.22f), fontWeight = FontWeight.Bold)) {
                            append(preview.match)
                        }
                        append(preview.after)
                    },
                    color = foreground, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ChatSearchButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val color = if (isCoui) CouiPageDefaults.themeColors(CouiTheme.isDark).title else MiuixTheme.colorScheme.onSurface
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(24.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = color.copy(alpha = if (enabled) 1f else 0.3f), modifier = Modifier.size(22.dp))
    }
}

/** A visible in-timeline marker without replacing Markdown, selection, or long-press handling. */
@Composable
internal fun ChatSearchHitMarker() {
    val accent = if (LocalUiStyle.current == AppearanceUiStyle.COUI) {
        CouiPageDefaults.themeColors(CouiTheme.isDark).accent
    } else MiuixTheme.colorScheme.primary
    Text(
        stringResource(R.string.chat_search_original_marker), color = accent, fontSize = 12.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)
            .background(accent.copy(alpha = 0.1f), RoundedCornerShape(8.dp)).padding(8.dp),
    )
}
