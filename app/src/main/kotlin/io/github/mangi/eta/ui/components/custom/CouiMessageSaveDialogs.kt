package io.github.mangi.eta.ui.components.custom

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.ui.components.coui.CouiConfirmDialog
import io.github.mangi.eta.ui.components.coui.CouiNoticeDialog

/** Native COUI presentation only. Controller owns all review, confirmation, persistence,
 * idempotency, limits and read-back semantics; no new writes or network operations here. */
@Composable
internal fun CouiMessageSaveDialogs(controller: MessageSaveController) {
    val result = controller.result
    val review = controller.review
    if (result != null) {
        CouiNoticeDialog(visible = true,
            title = if (result.error) "保存未确认" else "保存结果",
            message = result.message, confirmText = "确定", onDismissRequest = controller::dismissResult)
    } else if (review != null) {
        CouiConfirmDialog(visible = true,
            title = if (review.skill) "保存技能" else "保存记忆", message = null,
            confirmText = if (controller.saving) "保存中…" else "确认保存",
            confirmEnabled = !controller.saving, cancelEnabled = !controller.saving,
            onConfirm = controller::confirm,
            onDismissRequest = { if (!controller.saving) controller.dismiss() },
        ) {
            Column(Modifier.fillMaxWidth()) {
                CouiText(text = "请审核或编辑正文。仅保存到本地，不需要网络。", kind = CouiTextKind.ItemSummary)
                if (!review.skill) CouiText(
                    text = "记忆文件总上限为 1 MiB（UTF-8，含已有内容）；超限会提示，不会截断正文。",
                    modifier = Modifier.padding(top = 8.dp), kind = CouiTextKind.ItemSummary)
                BasicTextField(value = review.text, onValueChange = controller::edit,
                    enabled = !controller.saving,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                        .background(CouiTheme.colorScheme.background, RoundedCornerShape(12.dp))
                        .padding(12.dp).heightIn(min = 120.dp, max = 320.dp),
                    textStyle = TextStyle(color = CouiTheme.colorScheme.onSurface,
                        fontSize = 16.sp, lineHeight = 24.sp))
                review.error?.let { CouiText(text = it, modifier = Modifier.padding(top = 8.dp),
                    kind = CouiTextKind.ItemSummary, color = com.itos.coui.dialog.CouiDialogDefaults.themeColors().destructive) }
            }
        }
    }
}
