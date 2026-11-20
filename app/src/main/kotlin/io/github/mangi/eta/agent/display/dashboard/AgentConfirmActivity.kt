package io.github.mangi.eta.agent.display.dashboard

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Token-owned confirmation page. Delivery status is ACK-based; failures retain recovery to the original chat. */
internal class AgentConfirmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val token = intent?.getStringExtra(EXTRA_TOKEN).orEmpty()
        AgentConfirmGuard.init(applicationContext)
        val pending = AgentConfirmGuard.pendingByToken(token)
        val title = pending?.title ?: "审批已失效，请返回原会话核对"
        val risk = pending?.risk?.name.orEmpty()
        val conversation = pending?.conversationKey.orEmpty()
        val fields = pending?.fields?.map { it.label to it.value }.orEmpty()
        setContent {
            // Never capture an arbitrary other owner's display as approval evidence.
            val statuses by AgentConfirmGuard.submissionStatus.collectAsState()
            val submission = statuses[token].orEmpty()
            Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                        .background(Color(0xFF14171B))
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BasicText(
                        title,
                        style = TextStyle(color = Color(0xFFF2F4F7), fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                    )
                    BasicText(
                        "只批准原会话原运行中的精确操作；提交成功前不会消耗审批。不会自动重放旧坐标。",
                        style = TextStyle(color = Color(0xFF9AA4B2), fontSize = 12.sp),
                    )
                    if (risk.isNotBlank()) {
                        BasicText(
                            when (risk) {
                                "MONEY" -> "涉及资金，提交前请核对"
                                "PRIVACY" -> "涉及个人信息"
                                else -> "不可撤销操作"
                            },
                            style = TextStyle(color = Color(0xFFFFC44D), fontSize = 12.sp),
                        )
                    }
                    if (fields.isEmpty()) {
                        BasicText(
                            "没有可预览的字段，建议先「去接管」看一眼真实画面。",
                            style = TextStyle(color = Color(0xFF9AA4B2), fontSize = 12.sp),
                        )
                    }
                    Column(Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    fields.forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth()) {
                            BasicText(
                                label,
                                style = TextStyle(color = Color(0xFF9AA4B2), fontSize = 13.sp),
                                modifier = Modifier.width(72.dp),
                            )
                            BasicText(
                                value,
                                style = TextStyle(color = Color(0xFFF2F4F7), fontSize = 13.sp),
                            )
                        }
                    }
                    }
                    var steer by remember { mutableStateOf("") }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF1E232A))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.weight(1f)) {
                            if (steer.isEmpty()) {
                                BasicText("告诉 AI 要改什么…", style = TextStyle(color = Color(0xFF6E7884), fontSize = 13.sp))
                            }
                            BasicTextField(
                                value = steer,
                                onValueChange = { steer = it },
                                textStyle = TextStyle(color = Color(0xFFF2F4F7), fontSize = 13.sp),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        BasicText(
                            "发送",
                            style = TextStyle(color = Color(0xFF7BD8FF), fontSize = 13.sp, fontWeight = FontWeight.Medium),
                            modifier = Modifier.clickable {
                                if (steer.isNotBlank()) {
                                    AgentConfirmGuard.chooseFreeform(applicationContext, token, steer, conversationId = conversation)
                                }
                            }.padding(start = 10.dp),
                        )
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SheetButton("取消", Color(0xFFFF6B6B), Modifier.weight(1f)) {
                            // 拒绝 = 通过 steering 明确告诉 AI 停手，而不是只改本机状态机。
                            AgentConfirmGuard.choose(applicationContext, token, PromptOptionId.REJECT, conversation)
                        }
                        SheetButton("原会话接管", Color(0xFFB6BEC9), Modifier.weight(1f)) {
                            // 去接管 = 让悬浮窗自己铺成全屏，不再跳旧查看页。
                            // The legacy takeover launcher is global; do not hijack another owner.
                            if (AgentConfirmGuard.openOriginalConversation(this@AgentConfirmActivity, token)) finish()
                        }
                        SheetButton("确认", Color(0xFF5FE08C), Modifier.weight(1f), filled = true) {
                            // 批准 = 发一个可执行的许可让 AI 继续；不重放旧坐标（快照可能已过期）。
                            AgentConfirmGuard.choose(applicationContext, token, PromptOptionId.APPROVE, conversation)
                        }
                    }
                    if (submission.isNotBlank()) {
                        BasicText(submission, style = TextStyle(color = Color(0xFFFFD28C), fontSize = 13.sp))
                    }
                    SheetButton("返回原会话（保留未提交审批）", Color(0xFF7BD8FF), Modifier.fillMaxWidth()) {
                        if (AgentConfirmGuard.openOriginalConversation(this@AgentConfirmActivity, token)) finish()
                    }
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }

    companion object {
        const val EXTRA_TOKEN = "confirm_token"
        const val EXTRA_TITLE = "confirm_title"
        const val EXTRA_RISK = "confirm_risk"
        const val EXTRA_FIELDS = "confirm_fields"
        const val EXTRA_PREVIEW_PATH = "confirm_preview"
        const val EXTRA_CONVERSATION = "confirm_conversation"

        /** No owner-scoped frame is attached to this legacy API; never capture an unrelated display. */
        @Suppress("UNUSED_PARAMETER")
        fun capturePreview(context: Context, token: String): String = ""
    }
}

@Composable
private fun SheetButton(
    label: String,
    accent: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (filled) accent else accent.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            label,
            style = TextStyle(
                color = if (filled) Color(0xFF0A1013) else Color(0xFFF2F4F7),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}
