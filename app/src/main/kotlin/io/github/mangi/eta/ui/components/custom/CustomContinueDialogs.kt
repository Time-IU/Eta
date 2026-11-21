package io.github.mangi.eta.ui.components.custom

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import io.github.mangi.eta.ui.app.LocalResolvedDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import io.github.mangi.eta.ui.components.coui.messageDialogShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.mangi.eta.ui.components.coui.messageDialogSurface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Explicit review of the downgrade, not a silent replacement for native history branches. */
@Composable
internal fun CustomContinueDialogs(handler: CustomContinueHandler?) {
    val review = handler?.review ?: return
    if (io.github.mangi.eta.ui.app.LocalUiStyle.current == io.github.mangi.eta.data.model.AppearanceUiStyle.COUI) {
        io.github.mangi.eta.ui.components.coui.CouiConfirmDialog(
            visible = true, title = "在新对话继续", message = null,
            confirmText = if (handler.saving) "保存中…" else "确认继续",
            confirmEnabled = !handler.saving, cancelEnabled = !handler.saving,
            onConfirm = handler::confirm, onDismissRequest = { if (!handler.saving) handler.dismiss() },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ContinueReviewText("来源：${review.sourceRole}消息。此处使用引用继续，不是保留完整历史的原生分支。")
                ContinueReviewText("先保存原会话及当前草稿，再创建空白对话。新对话只填入下列引用，不继承原历史、附件或角色上下文，也不会自动发送。")
                ContinueReviewText("原会话：${review.conversationId}\n原消息：${review.sourceId}")
                ContinueReviewText(quoteWithDraft(review.selectedText, ""))
                ContinueReviewText("原草稿（仅保存在原会话，不带入新对话）：\n${review.originalDraft.ifEmpty { "（空）" }}")
                review.error?.let { ContinueReviewText(it) }
            }
        }
        return
    }
    val dark = LocalResolvedDarkTheme.current
    val foreground = if (dark) Color(0xFFF0F0F0) else Color(0xFF222222)
    val style = TextStyle(color = foreground, fontSize = 16.sp, lineHeight = 24.sp)
    Dialog(
        onDismissRequest = handler::dismiss,
        properties = DialogProperties(
            dismissOnBackPress = !handler.saving,
            dismissOnClickOutside = !handler.saving,
        ),
    ) {
        Column(
            Modifier.fillMaxWidth()
                .background(messageDialogSurface(), messageDialogShape())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BasicText("在新对话继续", style = style.copy(fontSize = 20.sp))
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                BasicText("来源：${review.sourceRole}消息。此处使用引用继续，不是保留完整历史的原生分支。", style = style)
                BasicText("先保存原会话及当前草稿，再创建空白对话。新对话只填入下列引用，不继承原历史、附件或角色上下文，也不会自动发送。", style = style)
                BasicText("原会话：${review.conversationId}\n原消息：${review.sourceId}", style = style)
                BasicText(quoteWithDraft(review.selectedText, ""), style = style)
                BasicText("原草稿（仅保存在原会话，不带入新对话）：\n${review.originalDraft.ifEmpty { "（空）" }}", style = style)
                review.error?.let { BasicText(it, style = style.copy(color = Color(0xFFD34747))) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                BasicText("取消", style = style, modifier = Modifier
                    .clickable(enabled = !handler.saving, onClick = handler::dismiss).padding(12.dp))
                BasicText(if (handler.saving) "保存中…" else "确认继续", style = style,
                    modifier = Modifier.clickable(enabled = !handler.saving, onClick = handler::confirm).padding(12.dp))
            }
        }
    }
}

@Composable
private fun ContinueReviewText(text: String) {
    com.itos.coui.text.Text(text = text, kind = com.itos.coui.text.CouiTextKind.ItemSummary)
}
