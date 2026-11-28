package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.model.LocalToolProfile
import io.github.mangi.eta.agent.model.LocalToolProfileStore
import io.github.mangi.eta.ui.model.AgentModelOptionUi
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val toolChoices = listOf(LocalToolProfile.FULL, LocalToolProfile.CODE, LocalToolProfile.PHONE)

private fun profileLabel(profile: LocalToolProfile): Int = when (profile) {
    LocalToolProfile.FULL -> R.string.local_profile_full
    LocalToolProfile.CHAT -> R.string.local_profile_chat
    LocalToolProfile.CODE -> R.string.local_profile_code
    LocalToolProfile.PHONE -> R.string.local_profile_phone
}

/** 胶囊两段的统一高度：指示器和图标都按这个尺寸排。 */
private val CapsuleSegmentHeight = 27.dp

/** 指示器滑动时长：与面板滑入同一条曲线，看起来是同一个动作带出的。 */
private val CapsuleIndicatorMs = 280

/** Two existing segments. Chat is still one tap; the tools segment opens the profile choices. */
@Composable
internal fun LocalProfileTopBarCapsule(
    model: AgentModelOptionUi?,
    compact: Boolean = false,
    workspaceActive: Boolean = false,
    onOpenWorkspace: () -> Unit = {},
    onCloseWorkspace: () -> Unit = {},
) {
    val profileKey = model?.let { it.providerId to it.modelId }
    val canSelectTemplate = LocalToolProfileStore.canSelectTemplate(profileKey?.first, profileKey?.second)
    var profile by remember(profileKey) {
        mutableStateOf(LocalToolProfileStore.current(profileKey?.first, profileKey?.second))
    }
    var showProfiles by remember(profileKey) { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val selectedIndex = if (profile == LocalToolProfile.CHAT) 0 else 1
    fun select(next: LocalToolProfile) {
        if (next.isTaskTemplate && !canSelectTemplate) {
            showProfiles = false
            return
        }
        LocalToolProfileStore.save(next, profileKey?.first, profileKey?.second)
        profile = next
        showProfiles = false
        if (workspaceActive) onCloseWorkspace()
        if (next == LocalToolProfile.CHAT) LocalProfileSwitchNotice.publish(LocalToolProfileSwitch.Chat)
    }

    // 半透明高亮：alpha 压到 0.42 一档，能透出底下的 surfaceContainer，
    // 不至于像一颗实心色块糊在顶栏上。指示器画在图标下层，所以图标不会被染混色。
    val selectedBrush = Brush.linearGradient(
        colors = listOf(
            MiuixTheme.colorScheme.primary.copy(alpha = 0.42f),
            MiuixTheme.colorScheme.primary.copy(alpha = 0.30f),
        ),
    )

    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainer)
            .padding(3.dp)
            .height(CapsuleSegmentHeight),
    ) {
        // 指示器先声明 = 画在底层，图标盖在它上面。之前画在上层，半透明时会把图标颜色染混。
        // 段宽固定，位移就是「索引 × 段宽」，不需要量父级宽度。
        val indicatorOffset by animateDpAsState(
            targetValue = CapsuleSegmentWidth * selectedIndex,
            animationSpec = tween(CapsuleIndicatorMs, easing = FastOutSlowInEasing),
            label = "capsule-indicator",
        )
        Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .padding(3.dp)
                .width(CapsuleSegmentWidth - 6.dp)
                .height(CapsuleSegmentHeight - 6.dp)
                .clip(CircleShape)
                .background(selectedBrush),
        )
        Row(
            modifier = Modifier.fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 第一段：纯聊天
            CapsuleSegment(
                icon = Icons.AutoMirrored.Rounded.Chat,
                label = stringResource(R.string.local_profile_chat),
                selected = selectedIndex == 0,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    select(LocalToolProfile.CHAT)
                },
            )
            // 第二段：工具选择；沿用相同宽度，当前模板用图标区分。
            CapsuleSegment(
                icon = when (profile) {
                    LocalToolProfile.CODE -> Icons.Rounded.Code
                    LocalToolProfile.PHONE -> Icons.Rounded.PhoneAndroid
                    else -> Icons.Rounded.Widgets
                },
                label = stringResource(R.string.local_profile_menu, stringResource(profileLabel(profile))),
                selected = selectedIndex == 1,
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    showProfiles = true
                },
            )
        }
    }
    if (showProfiles) {
        EtaWindowDialog(
            show = true,
            title = stringResource(R.string.local_profile_select_title),
            summary = stringResource(R.string.local_profile_select_help),
            onDismissRequest = { showProfiles = false },
        ) {
            toolChoices.forEach { option ->
                EtaActionPreference(
                    title = stringResource(profileLabel(option)),
                    summary = stringResource(when (option) {
                        LocalToolProfile.CODE -> R.string.local_profile_code_help
                        LocalToolProfile.PHONE -> R.string.local_profile_phone_help
                        else -> R.string.local_profile_full_help
                    }),
                    endIcon = if (profile == option) Icons.Rounded.Check else null,
                    modifier = Modifier.semantics { selected = profile == option },
                    enabled = !option.isTaskTemplate || canSelectTemplate,
                    onClick = { select(option) },
                )
            }
        }
    }
}

/** 胶囊单段的宽度：两段必须等宽，否则指示器按段宽算出的位移会与图标错位。 */
private val CapsuleSegmentWidth = 33.dp

/** 胶囊里的一段：图标 + 选中色。背景由滑动指示器画，这里只负责图标和点击。 */
@Composable
private fun CapsuleSegment(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(CapsuleSegmentWidth)
            .height(CapsuleSegmentHeight)
            .clip(CircleShape)
            .clickable { onClick() }
            .semantics { contentDescription = label; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = if (selected) {
                MiuixTheme.colorScheme.onPrimary
            } else {
                MiuixTheme.colorScheme.onSurfaceVariantSummary
            },
        )
    }
}
