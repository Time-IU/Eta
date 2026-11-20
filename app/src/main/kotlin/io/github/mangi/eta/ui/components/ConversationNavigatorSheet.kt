package io.github.mangi.eta.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.ConversationNavEntry
import io.github.mangi.eta.ui.model.ConversationNavRole
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 统一会话查找：目录和逐处全文搜索共用索引、分页与跳转。
 *
 * 默认按轮次列出每一条用户提问（目录），输入关键词后列出所有命中消息；
 * 点击跳回原消息；引用必须点击可见按钮，不将长按解释为隐式添加。
 */
@Composable
internal fun ConversationNavigatorSheet(
    show: Boolean,
    messagesKey: Any?,
    latestMessageId: String?,
    search: ChatSearchState,
    history: ChatSearchHistoryPagingState,
    historyIncomplete: Boolean,
    historyLoadState: io.github.mangi.eta.ui.model.ConversationHistoryLoadState,
    historyPaused: Boolean,
    loadedMessageCount: Int,
    onDismiss: () -> Unit,
    onJump: (String) -> Unit,
    onReference: (String) -> Unit = {},
    entriesFor: (String) -> List<ConversationNavEntry>,
) {
    var retainedEntries by remember { mutableStateOf<List<ConversationNavEntry>>(emptyList()) }
    OverlayBottomSheet(
        show = show,
        title = conversationLookupTitle(),
        onDismissRequest = onDismiss,
        insideMargin = DpSize(0.dp, 0.dp),
    ) {
        // One index and one pager for the panel, toolbar, and original-message positioning.
        val query = search.query
        val rows = remember(show, query, search.snapshot) {
            if (show && query.isNotBlank()) conversationSearchRows(search.snapshot?.documents.orEmpty(), search.hits)
            else emptyList()
        }
        val entries = remember(show, query, messagesKey, rows) {
            if (!show) retainedEntries
            else if (query.isBlank()) entriesFor("")
            else rows.map { it.entry }
        }
        androidx.compose.runtime.SideEffect { if (show) retainedEntries = entries }
        val listState = rememberLazyListState()
        // Do not reset the reading position when an older page arrives.
        LaunchedEffect(show, query) {
            if (show && query.isBlank() && entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
        }
        val sheetHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * 0.82f
        Column(Modifier.fillMaxWidth().height(sheetHeight).navigationBarsPadding()) {
            ChatSearchToolbar(
                open = show, query = query,
                hitIndex = search.index, hitCount = search.hits.size,
                searching = search.searching || (query.isNotBlank() && search.snapshot?.sourceMessages !== messagesKey),
                historyIncomplete = historyIncomplete,
                historyLoading = historyLoadState == io.github.mangi.eta.ui.model.ConversationHistoryLoadState.Loading || history.awaiting,
                historyPaused = historyPaused,
                historyFailed = historyLoadState == io.github.mangi.eta.ui.model.ConversationHistoryLoadState.Failed,
                historyStalled = history.stalled, loadedMessageCount = loadedMessageCount,
                onRetryHistory = history::retry,
                preview = search.preview,
                onOpen = search::openSearch, onQueryChange = search::changeQuery,
                onPrevious = { search.move(-1) }, onNext = { search.move(1) },
                onLocate = { search.hit?.let { onJump(it.messageId) } }, onClose = onDismiss,
            )
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) {
                if (entries.isEmpty()) item(key = "empty") {
                    Text(
                        text = if (query.isBlank()) stringResource(R.string.conversation_navigator_empty)
                            else stringResource(R.string.conversation_navigator_no_match, query),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                    )
                }
                items(count = entries.size, key = { index ->
                    entries[index].messageId + ":" + if (query.isBlank()) "outline" else rows.getOrNull(index)?.hit?.start
                }) { index ->
                    val entry = entries[index]
                    ConversationNavRow(
                        entry = entry,
                        onClick = {
                            if (query.isNotBlank()) rows.getOrNull(index)?.let { search.selectHit(it.originalHitIndex) }
                            onJump(entry.messageId)
                        },
                        onReference = { onReference(entry.messageId) },
                    )
                }
            }
            if (latestMessageId != null) TextButton(
                text = stringResource(R.string.conversation_navigator_jump_latest),
                onClick = { onJump(latestMessageId) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ConversationNavRow(
    entry: ConversationNavEntry,
    onClick: () -> Unit,
    onReference: () -> Unit,
) {
    val isPrompt = entry.role == ConversationNavRole.User
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ConversationNavBadge(entry)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.text.ifBlank { stringResource(R.string.conversation_navigator_blank) },
                style = MiuixTheme.textStyles.body2,
                color = if (isPrompt) MiuixTheme.colorScheme.onSurface else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontWeight = if (isPrompt) FontWeight.Medium else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            entry.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        TextButton(text = conversationReferenceLabel(), onClick = onReference, minWidth = 0.dp,
            insideMargin = PaddingValues(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** 左侧标签：提问显示轮次号，其余显示说话方，便于在搜索命中里分清是谁说的。 */
@Composable
private fun ConversationNavBadge(entry: ConversationNavEntry) {
    val isPrompt = entry.role == ConversationNavRole.User
    val label = when {
        isPrompt && entry.turn > 0 -> entry.turn.toString()
        entry.role == ConversationNavRole.User -> stringResource(R.string.conversation_navigator_you)
        entry.role == ConversationNavRole.System -> stringResource(R.string.conversation_navigator_system)
        else -> stringResource(R.string.conversation_navigator_assistant)
    }
    Box(
        modifier = Modifier
            .height(22.dp)
            .width(if (label.length >= 3) 34.dp else 24.dp)
            .background(
                color = if (isPrompt) {
                    MiuixTheme.colorScheme.primary.copy(alpha = 0.14f)
                } else {
                    MiuixTheme.colorScheme.surfaceContainerHigh
                },
                shape = RoundedCornerShape(7.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = if (isPrompt) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
