package io.github.mangi.eta.ui.components


import androidx.compose.foundation.text.input.rememberTextFieldState
import io.github.mangi.eta.ui.components.custom.LocalCustomComposer
import io.github.mangi.eta.ui.components.custom.LocalCustomContinue
import io.github.mangi.eta.ui.components.custom.rememberCustomContinue
import io.github.mangi.eta.ui.components.custom.CustomContinueDialogs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itos.coui.effect.couiButtonFeedback
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.shape.CouiShape
import com.itos.coui.text.CouiTextKind
import com.itos.coui.text.Text as CouiText
import com.itos.coui.theme.CouiTheme
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.browser.BrowserSessionSnapshot
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.agent.browser.AgentBrowserSession
import io.github.mangi.eta.agent.display.dashboard.AgentConfirmGuard
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.data.repository.ConversationBranchBoundary
import io.github.mangi.eta.ui.model.branchMessageOutline
import io.github.mangi.eta.ui.app.AgentConversationRevisionReducer
import io.github.mangi.eta.ui.app.LocalBlurEnabled
import io.github.mangi.eta.ui.app.LocalUiStyle
import io.github.mangi.eta.ui.markdown.StreamingMarkdownStateCache
import io.github.mangi.eta.ui.model.ConversationHistoryLoadState
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentCommand
import io.github.mangi.eta.ui.model.CommandConfirmUi
import io.github.mangi.eta.ui.model.CommandPaletteUi
import io.github.mangi.eta.ui.model.CommandResultUi
import io.github.mangi.eta.ui.model.AgentPaletteEntry
import io.github.mangi.eta.ui.model.ToolAccessUiState
import io.github.mangi.eta.ui.model.ToolToggleItem
import io.github.mangi.eta.ui.model.AgentContextUsageUi
import io.github.mangi.eta.ui.model.StreamingTokenEstimateTracker
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.MessageReferenceUi
import io.github.mangi.eta.ui.model.messageReference
import io.github.mangi.eta.ui.model.attachConversationReference
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.MessageEditUiState
import io.github.mangi.eta.ui.model.SearchJumpUiState
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import io.github.mangi.eta.ui.model.PendingImageUi
import io.github.mangi.eta.ui.model.QueuedMessageUi
import io.github.mangi.eta.ui.model.ResumableRunUiState
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.latestContextUsage
import io.github.mangi.eta.ui.voice.SpeechAutoReadAloud
import io.github.mangi.eta.ui.voice.SpeechPlaybackHost
import kotlin.math.exp
import kotlin.math.min
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.IconButtonDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 聊天主体：消息流 + 底部输入框。
 *
 * AI 对话使用正向时间线：第一条消息从对话区顶部开始，后续回复顺序向下追加。
 * 空 assistant 占位不参与布局，避免刚发送时出现一个无内容消息节点。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentChatBody(
    messages: List<AgentChatMessageUi>,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    isBranching: Boolean,
    input: String,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    queuedMessages: List<QueuedMessageUi> = emptyList(),
    inputSyncVersion: Int = 0,
    focusInputOnSync: Boolean = true,
    messageEdit: MessageEditUiState?,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onModelSelected: (String) -> Unit,
    onSubmit: (String) -> Unit,
    onInterruptAndSubmit: (String) -> Unit = onSubmit,
    onQueueMessage: (String) -> Unit = {},
    onSteerQueuedMessage: (String) -> Unit = {},
    onRecallQueuedMessage: (String, String) -> Unit = { _, _ -> },
    onRemoveQueuedMessage: (String) -> Unit = {},
    onStop: () -> Unit,
    isPaused: Boolean = false,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onBranchMessage: (String) -> Unit,
    onSelectReplyCandidate: (String, Int) -> Unit,
    /** 从这条消息分出新会话；null 表示当前场景不给入口。 */
    onForkMessage: ((String) -> Unit)? = null,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    hasEarlierMessages: Boolean = false,
    messageStartIndex: Int = 0,
    historyLoadState: ConversationHistoryLoadState = ConversationHistoryLoadState.Idle,
    onLoadEarlierMessages: () -> Unit = {},
    onHistoryViewportChanged: (Boolean) -> Unit = {},
    isDrawerOpen: Boolean = false,
    topInset: Dp = 0.dp,
    modifier: Modifier = Modifier,
    /** 跳到别的会话：点「另一个会话在等你回答」用。 */
    onOpenConversation: ((String) -> Unit)? = null,
    /** 空状态卡与输入框提示条上的「去接入」：跳到模型服务页。 */
    onOpenModelProviders: () -> Unit = {},
    characterName: String? = null,
    /** 草稿键（会话 id）：转给输入框，让未发送的文字按会话存下来。 */
    draftKey: String? = null,
    searchOpenRequested: Boolean = false,
    onSearchOpenConsumed: () -> Unit = {},
    searchJump: SearchJumpUiState? = null,
    onSearchJumpConsumed: () -> Unit = {},
    resumableRun: ResumableRunUiState? = null,
    onResumeInterruptedRun: () -> Unit = {},
    onDismissInterruptedRun: () -> Unit = {},
    /** 会话框上方的下拉小白条：上滑呼出会话导航。 */
    onOpenNavigator: () -> Unit,
    /** 斜杠命令与命令确认卡。 */
    commands: List<AgentCommand> = emptyList(),
    commandConfirm: CommandConfirmUi? = null,
    onConfirmCommand: () -> Unit = {},
    onDismissCommandConfirm: () -> Unit = {},
    /** 命令结果卡：/status、/effort、错误提示，停留到用户关掉。 */
    commandResult: CommandResultUi? = null,
    onDismissCommandResult: () -> Unit = {},
    /** 命令主动展开的面板（/help、/skills、/model）。 */
    commandPalette: CommandPaletteUi? = null,
    onPickPaletteEntry: (AgentPaletteEntry) -> Unit = {},
    onCloseCommandPalette: () -> Unit = {},
    onLongPressPaletteEntry: (AgentPaletteEntry) -> Unit = {},
    inputResetToken: Long = 0,
    toolAccess: ToolAccessUiState = ToolAccessUiState(),
    pendingMessageRefs: List<MessageReferenceUi> = emptyList(),
    onAttachReference: (MessageReferenceUi) -> Unit = {},
    onRemoveReference: (String) -> Unit = {},
    onRefreshToolAccess: () -> Unit = {},
    onToggleTool: (ToolToggleItem, Boolean) -> Unit = { _, _ -> },
) {
    // @、菜单和面板共用可见正文：用户／助手／思考均可引用，不携带隐藏注入。
    val referenceCandidates = remember(messages, messageStartIndex) {
        messages.mapIndexedNotNull { index, message ->
            message.messageReference(index + messageStartIndex + 1)
        }.takeLast(80)
    }
    val visibleMessages = remember(messages, messageEdit?.targetMessageId, messageEdit?.preserveFollowingMessages) {
        AgentConversationRevisionReducer.visibleMessagesForEdit(
            messages = messages,
            targetMessageId = messageEdit?.takeUnless { it.preserveFollowingMessages }?.targetMessageId,
        ).filterNot { message ->
            message is AgentMessageUi && message.content.isBlank()
        }
    }
    val initialScrollIndex = remember {
        visibleMessages.toTimelineEntries().size + if (hasEarlierMessages) 1 else 0
    }
    val scrollState = rememberLazyListState(initialFirstVisibleItemIndex = initialScrollIndex)
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val isKeyboardVisible = imeBottomPx > 0
    val browserSnapshot = LocalAgentBrowserSession.current?.snapshots?.collectAsState()?.value
        ?: BrowserSessionSnapshot()
    val streamingTokenTracker = remember { StreamingTokenEstimateTracker() }
    val streamingAssistant = remember(messages) {
        messages.asReversed().filterIsInstance<AgentMessageUi>().firstOrNull { it.isStreaming }
    }
    val streamingOutputTokensEstimate = remember(messages) {
        streamingTokenTracker.update(streamingAssistant?.id, streamingAssistant?.content)
    }
    val contextUsage = remember(messages, modelPickerState.selectedModel) {
        val usage = latestContextUsage(messages, modelPickerState.selectedModel)
        val latest = messages.asReversed().filterIsInstance<AgentMessageUi>().firstOrNull { it.usage != null }?.usage
        usage.copy(
            averageTokensPerSecond = latest?.generationTokensPerSecond ?: latest?.averageTokensPerSecond,
            latestUsage = usage.latestUsage ?: latest,
        )
    }
    val currentBrowserMessageId = remember(
        visibleMessages,
        browserSnapshot.available,
        browserSnapshot.lastAgentRunId,
        browserSnapshot.lastAgentToolCallId,
    ) {
        val runId = browserSnapshot.lastAgentRunId
        val toolCallId = browserSnapshot.lastAgentToolCallId
        if (!browserSnapshot.available || runId == null || toolCallId == null) {
            null
        } else {
            visibleMessages.lastOrNull { message ->
                message is ToolActivityMessageUi &&
                    message.toolName == "browser_use" &&
                    message.id.startsWith("$runId-tool-") &&
                    message.id.endsWith("-$toolCallId")
            }?.id
        }
    }
    var sentFromKeyboard by remember { mutableStateOf(false) }
    var keepBottomAnchored by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(isStreaming) {
        if (isStreaming && sentFromKeyboard) {
            keyboard?.hide()
            sentFromKeyboard = false
        }
    }

    LaunchedEffect(isDrawerOpen) {
        if (isDrawerOpen) {
            keyboard?.hide()
        }
    }

    val customComposer = rememberTextFieldState(initialText = input)
    val customContinue = rememberCustomContinue(customComposer)
    CompositionLocalProvider(
        io.github.mangi.eta.ui.components.custom.LocalCustomReference provides { messageId, selected ->
            attachConversationReference(messages, messageId, messageStartIndex, selected, onAttachReference)
        },
        LocalCustomComposer provides customComposer,
        LocalCustomContinue provides customContinue,
    ) {
        CustomContinueDialogs(customContinue)
        SpeechPlaybackHost {
            SpeechAutoReadAloud(messages = messages, isStreaming = isStreaming)
            AgentChatScaffold(
                visibleMessages = visibleMessages,
                hasMessages = visibleMessages.isNotEmpty() || hasEarlierMessages,
                scrollState = scrollState,
                draftKey = draftKey,
                onOpenModelProviders = onOpenModelProviders,
                input = input,
                modelPickerState = modelPickerState,
                onModelSelected = onModelSelected,
                isCompacting = isCompacting,
                isBranching = isBranching,
                contextUsage = contextUsage,
                streamingOutputTokensEstimate = streamingOutputTokensEstimate,
                isStreaming = isStreaming,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
                queuedMessages = queuedMessages,
                inputSyncVersion = inputSyncVersion,
                focusInputOnSync = focusInputOnSync,
                messageEdit = messageEdit,
                showEmptySuggestions = !isKeyboardVisible,
                keepBottomAnchored = keepBottomAnchored,
                onBottomAnchorChanged = { keepBottomAnchored = it },
                onSubmit = { text ->
                    sentFromKeyboard = true
                    // 发送即重新锚定底部：用户从历史上方直接发送时，同帧内 isStreaming 与
                    // 新消息一起到位，立即回到底部并恢复后续的流式平滑跟底。
                    keepBottomAnchored = true
                    onSubmit(text)
                },
                onInterruptAndSubmit = { text ->
                    sentFromKeyboard = true
                    keepBottomAnchored = true
                    onInterruptAndSubmit(text)
                },
                onSteerQueuedMessage = { id ->
                    keepBottomAnchored = true
                    onSteerQueuedMessage(id)
                },
                onRecallQueuedMessage = onRecallQueuedMessage,
                onRemoveQueuedMessage = onRemoveQueuedMessage,
                onReasoningEffortChange = onReasoningEffortChange,
                onCompactContext = onCompactContext,
                canCompactContext = canCompactContext,
                onStop = onStop,
                isPaused = isPaused,
                onPause = onPause,
                onResume = onResume,
                onQueueMessage = onQueueMessage,
                onOpenConversation = onOpenConversation,
                onOpenNavigator = onOpenNavigator,
                onAttachImage = onAttachImage,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onEditMessage = onEditMessage,
                onCancelMessageEdit = onCancelMessageEdit,
                onDeleteMessage = onDeleteMessage,
                onRegenerateMessage = onRegenerateMessage,
                onBranchMessage = onBranchMessage,
                onSelectReplyCandidate = onSelectReplyCandidate,
                onSuggestionClick = onSuggestionClick,
                onRunTraceClick = onRunTraceClick,
                onOpenBrowser = onOpenBrowser,
                currentBrowserMessageId = currentBrowserMessageId,
                hasEarlierMessages = hasEarlierMessages,
                messageStartIndex = messageStartIndex,
                historyLoadState = historyLoadState,
                onLoadEarlierMessages = onLoadEarlierMessages,
                onHistoryViewportChanged = onHistoryViewportChanged,
                topInset = topInset,
            searchOpenRequested = searchOpenRequested,
            onSearchOpenConsumed = onSearchOpenConsumed,
            searchJump = searchJump,
            onSearchJumpConsumed = onSearchJumpConsumed,
            resumableRun = resumableRun,
            onResumeInterruptedRun = onResumeInterruptedRun,
            onDismissInterruptedRun = onDismissInterruptedRun,

            commands = commands,
            commandConfirm = commandConfirm,
            onConfirmCommand = onConfirmCommand,
            onDismissCommandConfirm = onDismissCommandConfirm,
            commandResult = commandResult,
            onDismissCommandResult = onDismissCommandResult,
            commandPalette = commandPalette,
            onPickPaletteEntry = onPickPaletteEntry,
            onCloseCommandPalette = onCloseCommandPalette,
            onLongPressPaletteEntry = onLongPressPaletteEntry,
            inputResetToken = inputResetToken,
            toolAccess = toolAccess,
            referenceCandidates = referenceCandidates,
            pendingMessageRefs = pendingMessageRefs,
            onAttachReference = onAttachReference,
            onRemoveReference = onRemoveReference,
            onRefreshToolAccess = onRefreshToolAccess,
            onToggleTool = onToggleTool,
                modifier = modifier,
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AgentChatScaffold(
    draftKey: String? = null,
    visibleMessages: List<AgentChatMessageUi>,
    hasMessages: Boolean,
    /** 未接入模型时的「去接入」入口：一路透传到空状态卡与输入框提示条。 */
    onOpenModelProviders: () -> Unit = {},
    scrollState: LazyListState,
    input: String,
    modelPickerState: AgentModelPickerUiState,
    isCompacting: Boolean,
    isBranching: Boolean,
    contextUsage: AgentContextUsageUi,
    streamingOutputTokensEstimate: Int,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    queuedMessages: List<QueuedMessageUi>,
    inputSyncVersion: Int,
    focusInputOnSync: Boolean,
    messageEdit: MessageEditUiState?,
    showEmptySuggestions: Boolean,
    keepBottomAnchored: Boolean,
    onBottomAnchorChanged: (Boolean) -> Unit,
    onSubmit: (String) -> Unit,
    onInterruptAndSubmit: (String) -> Unit,
    onQueueMessage: (String) -> Unit,
    onSteerQueuedMessage: (String) -> Unit,
    onRecallQueuedMessage: (String, String) -> Unit,
    onRemoveQueuedMessage: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onModelSelected: (String) -> Unit,
    onStop: () -> Unit,
    isPaused: Boolean = false,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onEditMessage: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onDeleteMessage: (String) -> Unit,
    onRegenerateMessage: (String) -> Unit,
    onBranchMessage: (String) -> Unit,
    onSelectReplyCandidate: (String, Int) -> Unit,
    onForkMessage: ((String) -> Unit)? = null,
    onSuggestionClick: (String) -> Unit,
    onRunTraceClick: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenConversation: ((String) -> Unit)? = null,
    currentBrowserMessageId: String?,
    hasEarlierMessages: Boolean,
    messageStartIndex: Int,
    historyLoadState: ConversationHistoryLoadState,
    onLoadEarlierMessages: () -> Unit,
    onHistoryViewportChanged: (Boolean) -> Unit,
    topInset: Dp,
    modifier: Modifier = Modifier,
    searchOpenRequested: Boolean = false,
    onSearchOpenConsumed: () -> Unit = {},
    searchJump: SearchJumpUiState? = null,
    onSearchJumpConsumed: () -> Unit = {},
    resumableRun: ResumableRunUiState? = null,
    onResumeInterruptedRun: () -> Unit = {},
    onDismissInterruptedRun: () -> Unit = {},
    onOpenNavigator: () -> Unit,
    commands: List<AgentCommand> = emptyList(),
    commandConfirm: CommandConfirmUi? = null,
    onConfirmCommand: () -> Unit = {},
    onDismissCommandConfirm: () -> Unit = {},
    /** 命令结果卡：/status、/effort、错误提示，停留到用户关掉。 */
    commandResult: CommandResultUi? = null,
    onDismissCommandResult: () -> Unit = {},
    /** 命令主动展开的面板（/help、/skills、/model）。 */
    commandPalette: CommandPaletteUi? = null,
    onPickPaletteEntry: (AgentPaletteEntry) -> Unit = {},
    onCloseCommandPalette: () -> Unit = {},
    onLongPressPaletteEntry: (AgentPaletteEntry) -> Unit = {},
    inputResetToken: Long = 0,
    toolAccess: ToolAccessUiState = ToolAccessUiState(),
    referenceCandidates: List<MessageReferenceUi> = emptyList(),
    pendingMessageRefs: List<MessageReferenceUi> = emptyList(),
    onAttachReference: (MessageReferenceUi) -> Unit = {},
    onRemoveReference: (String) -> Unit = {},
    onRefreshToolAccess: () -> Unit = {},
    onToggleTool: (ToolToggleItem, Boolean) -> Unit = { _, _ -> },
) {
    val search = LocalConversationSearch.current ?: rememberChatSearch(visibleMessages)
    LaunchedEffect(searchOpenRequested) {
        if (searchOpenRequested) {
            onBottomAnchorChanged(false)
            search.openSearch()
            onSearchOpenConsumed()
        }
    }
    val surfaceColor = MiuixTheme.colorScheme.surface
    // 卡内输入态：审批卡展开输入框时收起底部聊天输入框，避免两个输入框同时在场。
    var cardInputActive by remember { mutableStateOf(false) }
    val frostEnabled = hasMessages && LocalBlurEnabled.current && isRuntimeShaderSupported()
    val messageBackdrop = rememberLayerBackdrop {
        // Backdrop 必须包含不透明底色，否则文字边缘模糊到透明区域时会出现黑边。
        drawRect(surfaceColor)
        drawContent()
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .then(if (cardInputActive) Modifier.imePadding() else Modifier),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(
            left = 0.dp,
            top = 0.dp,
            right = 0.dp,
            bottom = 0.dp,
        ),
        bottomBar = {
            if (!cardInputActive) AgentChatBottomBar(
                draftKey = draftKey,
                messageBackdrop = messageBackdrop.takeIf { frostEnabled },
                input = input,
                modelPickerState = modelPickerState,
                onModelSelected = onModelSelected,
                onOpenModelProviders = onOpenModelProviders,
                isCompacting = isCompacting,
                contextUsage = contextUsage,
                streamingOutputTokensEstimate = streamingOutputTokensEstimate,
                showContextUsage = hasMessages,
                isStreaming = isStreaming,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
                queuedMessages = queuedMessages,
                inputSyncVersion = inputSyncVersion,
                focusInputOnSync = focusInputOnSync,
                messageEdit = messageEdit,
                onSubmit = onSubmit,
                onInterruptAndSubmit = onInterruptAndSubmit,
                onQueueMessage = onQueueMessage,
                onSteerQueuedMessage = onSteerQueuedMessage,
                onRecallQueuedMessage = onRecallQueuedMessage,
                onRemoveQueuedMessage = onRemoveQueuedMessage,
                onReasoningEffortChange = onReasoningEffortChange,
                onCompactContext = onCompactContext,
                canCompactContext = canCompactContext,
                onStop = onStop,
            isPaused = isPaused,
            onPause = onPause,
            onResume = onResume,
                onAttachImage = onAttachImage,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onCancelMessageEdit = onCancelMessageEdit,
                onOpenNavigator = onOpenNavigator,
                commands = commands,
                commandConfirm = commandConfirm,
                onConfirmCommand = onConfirmCommand,
                onDismissCommandConfirm = onDismissCommandConfirm,
                commandResult = commandResult,
                onDismissCommandResult = onDismissCommandResult,
                commandPalette = commandPalette,
                onPickPaletteEntry = onPickPaletteEntry,
                onCloseCommandPalette = onCloseCommandPalette,
                onLongPressPaletteEntry = onLongPressPaletteEntry,
                inputResetToken = inputResetToken,
                toolAccess = toolAccess,
                referenceCandidates = referenceCandidates,
                pendingMessageRefs = pendingMessageRefs,
                onAttachReference = onAttachReference,
                onRemoveReference = onRemoveReference,
                onRefreshToolAccess = onRefreshToolAccess,
                onToggleTool = onToggleTool,
            )
        },
    ) { innerPadding ->
        val bottomPadding = innerPadding.calculateBottomPadding()
        val bodyTopInset = if (resumableRun != null) 0.dp else topInset
        Column(Modifier.fillMaxSize()) {
            if (resumableRun != null) {
                Box(Modifier.padding(top = topInset)) {
                    AgentResumableRunBanner(onResume = onResumeInterruptedRun, onDismiss = onDismissInterruptedRun, summary = resumableRun.summary)
                }
            }
            Box(Modifier.weight(1f)) {
        if (!hasMessages && !search.open) {
            EmptyChatState(
                showSuggestions = showEmptySuggestions,
                modelReady = modelPickerState.hasUsableModel,
                onOpenModelProviders = onOpenModelProviders,
                onSuggestionClick = onSuggestionClick,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = bodyTopInset, bottom = bottomPadding),
            )
        } else {
            AgentConversationMessages(
                visibleMessages = visibleMessages,
                conversationKey = draftKey.orEmpty(),
                onSubmit = onSubmit,
                onCardEditingChanged = { cardInputActive = it },
                cardEditing = cardInputActive,
                onOpenConversation = onOpenConversation,
                searchState = search,
                searchJump = searchJump,
                onSearchJumpConsumed = onSearchJumpConsumed,
                scrollState = scrollState,
                isStreaming = isStreaming,
                topInset = bodyTopInset,
                bottomInset = bottomPadding,
                keepBottomAnchored = keepBottomAnchored,
                onBottomAnchorChanged = onBottomAnchorChanged,
                onSuggestionClick = onSuggestionClick,
                onRunTraceClick = onRunTraceClick,
                onOpenBrowser = onOpenBrowser,
                onEditMessage = onEditMessage,
                onDeleteMessage = onDeleteMessage,
                onRegenerateMessage = onRegenerateMessage,
                onBranchMessage = onBranchMessage,
                onSelectReplyCandidate = onSelectReplyCandidate,
                messageActionsEnabled = !isStreaming && !isCompacting && !isBranching && messageEdit == null,
                editTargetMessageId = messageEdit?.targetMessageId,
                currentBrowserMessageId = currentBrowserMessageId,
                hasEarlierMessages = hasEarlierMessages,
                messageStartIndex = messageStartIndex,
                historyLoadState = historyLoadState,
                onLoadEarlierMessages = onLoadEarlierMessages,
                onHistoryViewportChanged = onHistoryViewportChanged,
                modifier = Modifier.fillMaxSize(),
            )
        }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun AgentConversationMessages(
    visibleMessages: List<AgentChatMessageUi>,
    scrollState: LazyListState,
    isStreaming: Boolean,
    bottomInset: Dp,
    /** 当前会话 key：审批卡按它做归属过滤，新会话不吃上一会话的卡。 */
    conversationKey: String = "",
    /** 问答卡选完后要把结果当成一条新用户消息提交，才能推动下一轮。 */
    onSubmit: ((String) -> Unit)? = null,
    /** 卡内是否正在输入：宿主据此收起底部输入框。 */
    onCardEditingChanged: ((Boolean) -> Unit)? = null,
    /** 卡内输入态：展开时把卡滚进可视区，别让键盘压着。 */
    cardEditing: Boolean = false,
    keepBottomAnchored: Boolean,
    onBottomAnchorChanged: (Boolean) -> Unit,
    assistantOverlay: Boolean = false,
    topInset: Dp = 0.dp,
    onSuggestionClick: (String) -> Unit = {},
    onRunTraceClick: () -> Unit = {},
    onOpenBrowser: () -> Unit = {},
    onOpenConversation: ((String) -> Unit)? = null,
    onEditMessage: (String) -> Unit = {},
    onDeleteMessage: (String) -> Unit = {},
    onRegenerateMessage: (String) -> Unit = {},
    onBranchMessage: (String) -> Unit = {},
    onSelectReplyCandidate: (String, Int) -> Unit = { _, _ -> },
    onForkMessage: ((String) -> Unit)? = null,
    messageActionsEnabled: Boolean = false,
    editTargetMessageId: String? = null,
    currentBrowserMessageId: String? = null,
    hasEarlierMessages: Boolean = false,
    messageStartIndex: Int = 0,
    historyLoadState: ConversationHistoryLoadState = ConversationHistoryLoadState.Idle,
    onLoadEarlierMessages: () -> Unit = {},
    onHistoryViewportChanged: (Boolean) -> Unit = {},
    searchState: ChatSearchState? = null,
    searchJump: SearchJumpUiState? = null,
    onSearchJumpConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // 上一版条目只作复用比对，不参与组合观察。
    val search = searchState ?: LocalConversationSearch.current ?: rememberChatSearch(visibleMessages)
    val timelineCache = remember { TimelineEntriesCache() }
    val preserveSearchBoundaries = search.open || timelineCache.searchWasOpened
    val timelineEntries = remember(visibleMessages, preserveSearchBoundaries) {
        visibleMessages.toTimelineEntries(
            previous = timelineCache.entries,
            preservePreviousWorkBoundaries = preserveSearchBoundaries,
        )
    }
    SideEffect {
        timelineCache.entries = timelineEntries
        if (search.open) timelineCache.searchWasOpened = true
    }
    val searchHit = search.hit
    val searchEntryIndex = remember(timelineEntries, searchHit?.messageId) {
        chatSearchTimelineIndex(timelineEntries, searchHit?.messageId)
    }
    val searchEntryKey = timelineEntries.getOrNull(searchEntryIndex)?.key

    val searchHistory = LocalConversationSearchPaging.current ?: rememberChatSearchHistoryPaging(
        open = search.open,
        query = search.query,
        canLoad = !isStreaming && editTargetMessageId == null,
        hasEarlier = hasEarlierMessages,
        before = messageStartIndex,
        loadState = historyLoadState,
        onLoadEarlier = {
            onHistoryViewportChanged(false)
            onBottomAnchorChanged(false)
            onLoadEarlierMessages()
        },
    )
    // Only explicit navigation may move the viewport. Newly discovered hits/history prepends
    // update ordinal counts but never jump to an earlier match or reset the reading offset.
    // LazyColumn's stable timeline keys retain the actual reading anchor during page insertion.
    var handledSearchNavigation by remember { mutableIntStateOf(0) }
    LaunchedEffect(search.open, search.query, search.navigationVersion) {
        if (search.navigationVersion != handledSearchNavigation) {
            handledSearchNavigation = search.navigationVersion
            if (search.open && searchHit != null && searchEntryIndex >= 0) {
                onBottomAnchorChanged(false)
                scrollState.scrollToItem(searchEntryIndex + if (hasEarlierMessages) 1 else 0)
            }
        }
    }
    val activeWorkProcessKey = if (isStreaming) {
        (timelineEntries.lastOrNull() as? AgentTimelineEntry.WorkProcess)?.key
    } else {
        null
    }
    // 复制按钮只出现在每轮对话的最终结果上，中间步骤的过渡文本不提供复制入口。
    // 流式进行中当前这一轮尚未收尾，此时的“最后一条正文”只是中间步骤，不标记。
    val finalResultMessageIds = remember(visibleMessages, isStreaming) {
        resolveFinalResultMessageIds(visibleMessages, isStreaming = isStreaming)
    }
    val branchMessageIds = remember(visibleMessages, isStreaming) {
        ConversationBranchBoundary.eligibleMessageIds(visibleMessages.branchMessageOutline(), running = isStreaming)
    }
    // 流式消息的渲染会话按 id 提升到列表层持有：item 滚出视口被 LazyColumn 销毁后，
    // 滑回时复用同一解析会话与打字机进度，避免整段内容重新解析并重放显现动画。
    val streamingMarkdownCache = remember { StreamingMarkdownStateCache() }
    val streamingMarkdownStates = streamingMarkdownCache.states
    val streamingMessageIds = remember(visibleMessages) {
        visibleMessages.mapNotNullTo(mutableSetOf()) { message ->
            when (message) {
                is AgentMessageUi -> message.id.takeIf { message.isStreaming }
                is ThinkingMessageUi -> message.id.takeIf { message.isStreaming }
                else -> null
            }
        }.also { ids ->
            ids.forEach { streamingMarkdownCache.getOrCreate(it) }
        }
    }
    LaunchedEffect(streamingMarkdownCache, streamingMessageIds) {
        snapshotFlow { streamingMarkdownCache.unusedMessageIds(streamingMessageIds) }
            .collect { unused ->
                if (unused.isNotEmpty()) streamingMarkdownCache.discardUnused(streamingMessageIds)
            }
    }
    val earlierMessagesItemCount = if (hasEarlierMessages) 1 else 0
    // 审批卡：AI 被拦下来的一步。挂在列表末尾跟消息一起滚，不另占浮层。
    val pendingApprovals by AgentConfirmGuard.pendings.collectAsState()
    val confirmContext = LocalContext.current
    val approvalItemCount = pendingApprovals.count { it.conversationKey.isNotBlank() && it.conversationKey != conversationKey } +
        if (pendingApprovals.any { it.conversationKey.isNotBlank() && it.conversationKey == conversationKey }) 1 else 0
    val bottomItemIndex = timelineEntries.size + earlierMessagesItemCount + approvalItemCount
    val isUserDragging by scrollState.interactionSource.collectIsDraggedAsState()
    val isAtBottom by remember(scrollState) {
        derivedStateOf { !scrollState.canScrollForward }
    }
    val isListMeasured by remember(scrollState) {
        derivedStateOf {
            scrollState.layoutInfo.let { it.totalItemsCount > 0 && it.viewportEndOffset > it.viewportStartOffset }
        }
    }
    val densityScale = LocalDensity.current.density
    val coroutineScope = rememberCoroutineScope()
    var expandSuppressVersion by remember { mutableIntStateOf(0) }
    var suppressAutoReanchor by remember { mutableStateOf(false) }

    LaunchedEffect(expandSuppressVersion) {
        if (expandSuppressVersion == 0) return@LaunchedEffect
        suppressAutoReanchor = true
        delay(EXPAND_REANCHOR_SUPPRESS_MS)
        suppressAutoReanchor = false
    }

    val onExpandItem = remember(onBottomAnchorChanged) {
        {
            suppressAutoReanchor = true
            expandSuppressVersion += 1
            onBottomAnchorChanged(false)
        }
    }

    LaunchedEffect(
        isListMeasured,
        isUserDragging,
        isAtBottom,
        keepBottomAnchored,
        suppressAutoReanchor,
        search.open,
    ) {
        if (!isListMeasured) return@LaunchedEffect
        val next = resolveKeepBottomAnchored(
            current = keepBottomAnchored,
            isUserDragging = isUserDragging,
            isAtBottom = isAtBottom,
            suppressAutoReanchor = suppressAutoReanchor || search.open,
        )
        if (next != keepBottomAnchored) {
            onBottomAnchorChanged(next)
        }
    }

    val tailMessage = visibleMessages.lastOrNull() as? AgentMessageUi
    val currentTailMessage by rememberUpdatedState(tailMessage)
    // 显现进度属于尾部条目的高频状态；列表作用域只观察离散化后的布尔值。
    val isTailRendering by remember {
        derivedStateOf {
            currentTailMessage?.let { message ->
                streamingMarkdownStates[message.id]?.let { state ->
                    state.revealedContent != message.content
                }
            } == true
        }
    }
    var isBottomSettling by remember { mutableStateOf(isStreaming) }

    LaunchedEffect(isStreaming, isTailRendering, keepBottomAnchored, isUserDragging) {
        if (!keepBottomAnchored || isUserDragging) {
            isBottomSettling = false
        } else if (isStreaming || isTailRendering) {
            isBottomSettling = true
        } else if (isBottomSettling) {
            // 显现完成后还会切换稳定排版并插入操作行，等其完成测量并对齐底部后收口。
            withFrameNanos { }
            withFrameNanos { }
            withTimeoutOrNull(BOTTOM_SETTLE_TIMEOUT_MS) {
                snapshotFlow { !scrollState.canScrollForward }.first { it }
            }
            isBottomSettling = false
        }
    }

    val historyPaging = rememberChatHistoryPaging(
        scrollState = scrollState,
        entries = timelineEntries,
        messageStartIndex = messageStartIndex,
        messageCount = visibleMessages.size,
        hasEarlier = hasEarlierMessages,
        loadState = historyLoadState,
        // Search owns page demand while open; viewport paging must not race or retry a failure.
        enabled = !search.open && !isStreaming && editTargetMessageId == null,
        canReleasePages = !search.open && !isTailRendering && !isBottomSettling,
        keepBottomAnchored = keepBottomAnchored,
        onBottomAnchorChanged = onBottomAnchorChanged,
        onLoadEarlier = onLoadEarlierMessages,
        onViewportChanged = onHistoryViewportChanged,
    )

    val shouldFollowBottom by rememberUpdatedState(
        !search.open && resolveBottomFollowEnabled(
            isStreaming = isStreaming,
            keepBottomAnchored = keepBottomAnchored,
            isUserDragging = isUserDragging,
            isBottomSettling = isBottomSettling,
        )
    )
    val currentBottomItemIndex by rememberUpdatedState(bottomItemIndex)
    val bottomFollowDecisions = remember(scrollState) {
        Channel<BottomFollowDecision>(Channel.CONFLATED)
    }

    LaunchedEffect(isStreaming, keepBottomAnchored, search.open) {
        if (search.open) return@LaunchedEffect
        if (isStreaming && keepBottomAnchored) {
            suppressAutoReanchor = false
        }
        if (shouldRequestInitialBottom(
                isStreaming = isStreaming,
                keepBottomAnchored = keepBottomAnchored,
                isUserDragging = isUserDragging,
            )
        ) {
            scrollState.requestScrollToItem(currentBottomItemIndex)
        }
    }

    // 搜索命中跳转：先解除底部锚定（否则流式运行中的跟底会把视图拉回尾部），
    // 再滚到命中条目并临时高亮；全程走同一次观察到的条目索引。
    var highlightMessageId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(searchJump?.token) {
        val jump = searchJump ?: return@LaunchedEffect
        val index = timelineEntries.indexOfFirst { entry -> entry.containsMessage(jump.messageId) }
        if (index < 0) {
            onSearchJumpConsumed()
            return@LaunchedEffect
        }
        onBottomAnchorChanged(false)
        scrollState.animateScrollToItem(index + earlierMessagesItemCount)
        onBottomAnchorChanged(false)
        highlightMessageId = jump.messageId
        delay(SEARCH_HIGHLIGHT_DURATION_MS)
        highlightMessageId = null
        onSearchJumpConsumed()
    }

    // 流式输出及渲染收尾期间发布最新的跟底距离。历史消息中的步骤/思考展开同样会改变
    // 列表高度，但那是用户主动查看内容，不能被误判成尾部文字增长。
    LaunchedEffect(scrollState) {
        snapshotFlow {
            val layoutInfo = scrollState.layoutInfo
            val sentinel = layoutInfo.visibleItemsInfo.firstOrNull { item ->
                item.key == ChatBottomSentinelKey
            }
            BottomFollowLayout(
                enabled = shouldFollowBottom,
                bottomItemIndex = currentBottomItemIndex,
                sentinelBottom = sentinel?.let { it.offset + it.size },
                // 输入器高度属于滚动内容的 bottom inset，而不是滚动容器高度。
                // 跟底目标应是 afterContentPadding 之前的正文边界。
                viewportEnd = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding,
                lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index,
            )
        }
            .distinctUntilChanged()
            .collect { layout ->
                val decision = resolveBottomFollowDecision(
                    enabled = layout.enabled,
                    bottomItemIndex = layout.bottomItemIndex,
                    sentinelBottom = layout.sentinelBottom,
                    viewportEnd = layout.viewportEnd,
                    lastVisibleIndex = layout.lastVisibleIndex,
                )
                bottomFollowDecisions.trySend(decision)
            }
    }

    // 一个持续存在的帧时钟从当前屏幕位置追向最新目标。新字符继续到达时只更新目标，
    // 不取消并重启动画，因此速度连续；用户开始拖动后，enabled=false 会立即停止跟随。
    LaunchedEffect(scrollState, bottomFollowDecisions) {
        var remainingDistancePx = 0f
        var requestIndex: Int? = null
        var previousFrameNanos = 0L

        fun accept(decision: BottomFollowDecision) {
            remainingDistancePx = decision.scrollByPx.toFloat()
            requestIndex = decision.requestIndex
        }

        while (currentCoroutineContext().isActive) {
            if (remainingDistancePx <= 0f && requestIndex == null) {
                accept(bottomFollowDecisions.receive())
                previousFrameNanos = 0L
            }
            while (true) {
                val latest = bottomFollowDecisions.tryReceive().getOrNull() ?: break
                accept(latest)
            }

            if (!shouldFollowBottom) {
                remainingDistancePx = 0f
                requestIndex = null
                continue
            }

            val targetIndex = requestIndex
            if (targetIndex != null) {
                scrollState.requestScrollToItem(targetIndex)
                requestIndex = null
                remainingDistancePx = 0f
                continue
            }
            if (remainingDistancePx <= 0f) continue

            val frameNanos = withFrameNanos { it }
            val elapsedSeconds = if (previousFrameNanos == 0L) {
                1f / 60f
            } else {
                ((frameNanos - previousFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            }
            previousFrameNanos = frameNanos

            while (true) {
                val latest = bottomFollowDecisions.tryReceive().getOrNull() ?: break
                accept(latest)
            }
            if (!shouldFollowBottom || requestIndex != null || remainingDistancePx <= 0f) continue

            val step = smoothBottomFollowStep(
                distancePx = remainingDistancePx,
                elapsedSeconds = elapsedSeconds,
                density = densityScale,
            )
            var consumedStep = 0f
            try {
                scrollState.scroll {
                    scrollBy(step)
                    consumedStep = step
                }
                remainingDistancePx = if (consumedStep > 0f) {
                    (remainingDistancePx - consumedStep).coerceAtLeast(0f)
                } else {
                    0f
                }
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                remainingDistancePx = 0f
            }
        }
    }

    // 滚动层保持整屏，输入器作为后绘制浮层；输入器高度进入列表的
    // afterContentPadding，确保跟到底部时最后一行停在输入器上方。
    CompositionLocalProvider(LocalOnChatItemExpand provides onExpandItem) {
        Box(modifier = modifier.clipToBounds()) {
            Column(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = scrollState,
                    verticalArrangement = Arrangement.Top,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .scrollEndHaptic()
                        .nestedScroll(historyPaging.scrollConnection)
                        .overScrollVertical(),
                    contentPadding = PaddingValues(
                        top = topInset + 8.dp,
                        bottom = bottomInset + 14.dp,
                    ),
                    overscrollEffect = null,
                ) {
                    if (hasEarlierMessages) {
                        item(key = ChatHistoryBoundaryKey) {
                            AgentHistoryLoadStatus(
                                state = historyLoadState,
                                enabled = !isStreaming && editTargetMessageId == null &&
                                    (!search.open || (search.query.isNotBlank() && !searchHistory.awaiting)),
                                onRetry = if (search.open) searchHistory::retry else historyPaging.retry,
                            )
                        }
                    }
                    items(
                        items = timelineEntries,
                        key = { it.key },
                        contentType = { it.contentType() },
                    ) { entry ->
                        val itemModifier = Modifier.animateItem(
                            fadeInSpec = tween(durationMillis = 180),
                            placementSpec = null,
                            // 历史轮次被编辑、删除或重新生成时必须立即退出；退出动画会让已从
                            // 状态中裁掉的旧消息继续绘制，并与同位置的新流式消息短暂重叠。
                            fadeOutSpec = null,
                        )
                        Column(modifier = itemModifier.then(
                            if (highlightMessageId != null && entry.containsMessage(highlightMessageId!!)) Modifier.searchHitHighlight() else Modifier,
                        )) {
                            if (search.open && entry.key == searchEntryKey) ChatSearchHitMarker()
                            when (entry) {
                                is AgentTimelineEntry.Message -> {
                                    val message = entry.message
                                    ChatMessageItem(
                                        message = message,
                                        assistantOverlay = assistantOverlay,
                                        retainedStreamingState = (message as? AgentMessageUi)
                                            ?.let { streamingMarkdownStates[it.id] },
                                        onSuggestionClick = onSuggestionClick,
                                        onRunTraceClick = onRunTraceClick,
                                        onOpenBrowser = onOpenBrowser,
                                        showBrowserShortcut = message is ToolActivityMessageUi &&
                                            message.toolName == "browser_use" &&
                                            message.id == currentBrowserMessageId,
                                        showCopyAction = message !is AgentMessageUi ||
                                            message.characterEditable || message.id in finalResultMessageIds,
                                        showMessageActions = !assistantOverlay && (
                                            message.id in finalResultMessageIds ||
                                                (message is AgentMessageUi && message.characterEditable)
                                            ),
                                        messageActionsEnabled = messageActionsEnabled,
                                        showBranchAction = !assistantOverlay && message.id in branchMessageIds,
                                        onBranchMessage = onBranchMessage,
                                        isEditing = message.id == editTargetMessageId,
                                        onEditMessage = onEditMessage,
                                        onDeleteMessage = onDeleteMessage,
                                        onRegenerateMessage = onRegenerateMessage,
                                        onSelectReplyCandidate = onSelectReplyCandidate,
                                    )
                                }

                                is AgentTimelineEntry.WorkProcess -> {
                                    AgentWorkProcess(
                                        id = entry.key,
                                        active = entry.key == activeWorkProcessKey,
                                        messages = entry.messages,
                                        assistantOverlay = assistantOverlay,
                                        onOpenBrowser = onOpenBrowser,
                                        currentBrowserMessageId = currentBrowserMessageId,
                                        retainedStreamingStates = streamingMarkdownStates,
                                    )
                                }
                            }
                        }
                    }
            pendingApprovals.firstOrNull {
                it.conversationKey.isNotBlank() && it.conversationKey == conversationKey
            }?.let { approval ->
                item(key = "approval-card") {
                    AgentApprovalCard(
                        approval = AgentApprovalUi(
                            token = approval.token,
                            title = approval.title,
                            // 提问不是危险动作，别把「不可撤销」贴到问卷上。
                            riskLabel = if (approval.source == io.github.mangi.eta.agent.display.dashboard.PendingApproval.SOURCE_ASK) "" else approval.riskLabel,
                            fields = approval.fields.map { it.label to it.value },
                            quiet = approval.quiet,
                            repeatCount = approval.repeatCount,
                            body = approval.body,
                            options = approval.options.map {
                                AgentApprovalOptionUi(it.id, it.label, it.description)
                            },
                            allowMultiple = approval.allowMultiple,
                        ),
                        // Guard owns asynchronous delivery and ACK. Never send again through the selected composer.
                        onChoose = { id ->
                            AgentConfirmGuard.choose(confirmContext, approval.token, id, approval.conversationKey)
                        },
                        onChooseMany = { ids ->
                            AgentConfirmGuard.chooseMany(confirmContext, approval.token, ids, approval.conversationKey)
                        },
                        onChooseFreeform = { text, extraIds ->
                            AgentConfirmGuard.chooseFreeform(confirmContext, approval.token, text, extraIds, approval.conversationKey)
                        },
                        onEditingChanged = { onCardEditingChanged?.invoke(it) },
                        onSkip = {
                            AgentConfirmGuard.skip(confirmContext, approval.token, approval.conversationKey)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
            pendingApprovals.filter {
                it.conversationKey.isNotBlank() && it.conversationKey != conversationKey
            }.forEach { other ->
                item(key = "approval-elsewhere-${other.token}") {
                    // 一行灰字太容易被划过去，改成带底色的卡片：标题 + 问题 + 去处理。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .background(
                                color = MiuixTheme.colorScheme.primary.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(16.dp),
                            )
                            .clickable(enabled = onOpenConversation != null) {
                                onOpenConversation?.invoke(other.conversationKey)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "另一个会话在等你",
                                style = MiuixTheme.textStyles.body2,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.primary,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "去处理",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            text = "「${other.title}」",
                            style = MiuixTheme.textStyles.footnote1,
                            color = MiuixTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
                    item(key = ChatBottomSentinelKey) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(1.dp),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = !search.open && !keepBottomAnchored && !isAtBottom,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = bottomInset + 8.dp),
                enter = fadeIn(tween(160)) + scaleIn(tween(180), initialScale = 0.82f),
                exit = fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.86f),
            ) {
                val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
                val couiDark = CouiTheme.isDark
                IconButton(
                    modifier = if (isCoui) {
                        Modifier
                            .dropShadow(
                                shape = CircleShape,
                                shadow = Shadow(
                                    radius = 10.dp,
                                    color = Color.Black,
                                    alpha = if (couiDark) 0.24f else 0.08f,
                                ),
                            )
                            .border(
                                width = 0.5.dp,
                                color = if (couiDark) Color(0x22FFFFFF) else Color(0x10000000),
                                shape = CircleShape,
                            )
                    } else {
                        Modifier
                            .dropShadow(
                                shape = CircleShape,
                                shadow = Shadow(radius = 8.dp, color = Color.Black, alpha = 0.08f),
                            )
                            .squircleBorder(
                                width = 0.5.dp,
                                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.55f),
                                cornerRadius = IconButtonDefaults.CornerRadius,
                            )
                    },
                    onClick = {
                        suppressAutoReanchor = false
                        onBottomAnchorChanged(true)
                        coroutineScope.launch {
                            scrollState.animateScrollToItem(bottomItemIndex)
                        }
                    },
                    backgroundColor = if (isCoui) {
                        if (couiDark) Color(0xFF24262B) else Color.White
                    } else {
                        MiuixTheme.colorScheme.surfaceContainerHigh
                    },
                    minWidth = 36.dp,
                    minHeight = 36.dp,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = stringResource(R.string.ui_back_to_bottom_32282e),
                        modifier = Modifier.size(16.dp),
                        tint = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                    )
                }
            }
        }
    }
}

internal fun chatSearchTimelineIndex(entries: List<AgentTimelineEntry>, messageId: String?): Int =
    if (messageId == null) -1 else entries.indexOfFirst { entry ->
        when (entry) {
            is AgentTimelineEntry.Message -> entry.message.id == messageId
            is AgentTimelineEntry.WorkProcess -> entry.messages.any { it.id == messageId }
        }
    }

private data class BottomFollowLayout(
    val enabled: Boolean,
    val bottomItemIndex: Int,
    val sentinelBottom: Int?,
    val viewportEnd: Int,
    val lastVisibleIndex: Int?,
)

internal data class BottomFollowDecision(
    val scrollByPx: Int = 0,
    val requestIndex: Int? = null,
)

internal fun resolveBottomFollowDecision(
    enabled: Boolean,
    bottomItemIndex: Int,
    sentinelBottom: Int?,
    viewportEnd: Int,
    lastVisibleIndex: Int?,
): BottomFollowDecision {
    if (!enabled) return BottomFollowDecision()
    val overflow = sentinelBottom?.minus(viewportEnd)
    return when {
        overflow != null && overflow > 0 -> BottomFollowDecision(scrollByPx = overflow)
        sentinelBottom == null &&
            lastVisibleIndex != null &&
            lastVisibleIndex < bottomItemIndex -> BottomFollowDecision(requestIndex = bottomItemIndex)
        else -> BottomFollowDecision()
    }
}

internal fun smoothBottomFollowStep(
    distancePx: Float,
    elapsedSeconds: Float,
    density: Float,
): Float {
    if (distancePx <= 0f || elapsedSeconds <= 0f) return 0f
    if (distancePx <= BOTTOM_FOLLOW_SNAP_DISTANCE_PX) return distancePx

    val frameSeconds = elapsedSeconds.coerceAtMost(BOTTOM_FOLLOW_MAX_FRAME_SECONDS)
    val easedStep = distancePx * (1f - exp(-frameSeconds / BOTTOM_FOLLOW_RESPONSE_SECONDS))
    val speedLimitedStep = BOTTOM_FOLLOW_MAX_SPEED_DP_PER_SECOND * density * frameSeconds
    return min(distancePx, min(easedStep.coerceAtLeast(BOTTOM_FOLLOW_MIN_STEP_PX), speedLimitedStep))
}

/** 该列表条目是否包含指定消息：用于搜索命中定位与高亮。 */
internal fun AgentTimelineEntry.containsMessage(messageId: String): Boolean = when (this) {
    is AgentTimelineEntry.Message -> message.id == messageId
    is AgentTimelineEntry.WorkProcess -> messages.any { message -> message.id == messageId }
}

/** 搜索命中后的临时高亮底色。 */
@Composable
private fun Modifier.searchHitHighlight(): Modifier = background(
    color = MiuixTheme.colorScheme.primary.copy(alpha = 0.15f),
    shape = RoundedCornerShape(16.dp),
)

internal sealed interface AgentTimelineEntry {
    val key: String

    data class Message(
        val message: AgentChatMessageUi,
    ) : AgentTimelineEntry {
        override val key: String = message.id
    }

    data class WorkProcess(
        override val key: String,
        val messages: List<AgentChatMessageUi>,
    ) : AgentTimelineEntry
}

/**
 * 投影层只替换真正变化的消息实例；工作过程分组若成员实例全部未变，就复用上一版条目，
 * 让已结束的分组在正文流式刷新时保持参数同一性并跳过重组。
 */
internal fun List<AgentChatMessageUi>.toTimelineEntries(
    previous: List<AgentTimelineEntry> = emptyList(),
    preservePreviousWorkBoundaries: Boolean = false,
): List<AgentTimelineEntry> = buildList {
    val previousWorkByMessageId = previous.asSequence()
        .filterIsInstance<AgentTimelineEntry.WorkProcess>()
        .flatMap { entry -> entry.messages.asSequence().map { it.id to entry } }
        .toMap()
    val workMessages = mutableListOf<AgentChatMessageUi>()

    fun flushWorkProcess() {
        if (workMessages.isEmpty()) return
        // 历史页可能从同一工作过程的中间开始；前插补齐后保留已显示分组的身份和滚动锚点。
        val messageIds = workMessages.mapTo(HashSet()) { it.id }
        val previousEntry = workMessages.firstNotNullOfOrNull { previousWorkByMessageId[it.id] }
            ?.takeIf { entry -> entry.messages.all { it.id in messageIds } }
        val key = previousEntry?.key ?: "work-${workMessages.first().id}"
        val reusable = previousEntry?.takeIf { entry ->
            entry.messages.size == workMessages.size &&
                entry.messages.indices.all { index -> entry.messages[index] === workMessages[index] }
        }
        add(reusable ?: AgentTimelineEntry.WorkProcess(key = key, messages = workMessages.toList()))
        workMessages.clear()
    }

    this@toTimelineEntries.forEach { message ->
        if (message.isWorkProcessMessage()) {
            // Automatic search paging must not insert more steps *inside* the top visible,
            // possibly expanded process item: a stable item key alone cannot preserve its
            // internal reading offset. Keep that existing group intact; prepend a separate
            // process group. Normal (non-search) grouping remains unchanged.
            if (preservePreviousWorkBoundaries && workMessages.isNotEmpty() &&
                previousWorkByMessageId[message.id]?.messages?.firstOrNull()?.id == message.id) {
                flushWorkProcess()
            }
            workMessages += message
        } else {
            flushWorkProcess()
            add(AgentTimelineEntry.Message(message))
        }
    }
    flushWorkProcess()
}

private fun AgentTimelineEntry.contentType(): Any = when (this) {
    is AgentTimelineEntry.WorkProcess -> AgentTimelineEntry.WorkProcess::class
    is AgentTimelineEntry.Message -> message::class
}

private fun AgentChatMessageUi.isWorkProcessMessage(): Boolean =
    this is ThinkingMessageUi || this is ToolActivityMessageUi || this is ToolSummaryMessageUi

/**
 * 一轮对话（两条用户消息之间）里最后一条 Agent 正文视为最终结果，其余为中间步骤。
 * 流式传输期间当前轮次尚未结束，最后一轮不标记，等传输结束后复制按钮才出现；
 * 之前已结束轮次的最终结果不受影响。
 */
internal fun resolveFinalResultMessageIds(
    messages: List<AgentChatMessageUi>,
    isStreaming: Boolean = false,
): Set<String> {
    val ids = LinkedHashSet<String>()
    var lastAgentMessageId: String? = null
    messages.forEach { message ->
        when (message) {
            is UserMessageUi -> {
                lastAgentMessageId?.let(ids::add)
                lastAgentMessageId = null
            }
            is AgentMessageUi -> lastAgentMessageId = message.id
            else -> Unit
        }
    }
    if (!isStreaming) {
        lastAgentMessageId?.let(ids::add)
    }
    return ids
}

@Composable
private fun AgentChatBottomBar(
    draftKey: String? = null,
    messageBackdrop: LayerBackdrop? = null,
    input: String,
    modelPickerState: AgentModelPickerUiState,
    onModelSelected: (String) -> Unit = {},
    /** 未接入模型时输入框上方提示条的「去接入」。 */
    onOpenModelProviders: () -> Unit = {},
    isCompacting: Boolean,
    contextUsage: AgentContextUsageUi,
    streamingOutputTokensEstimate: Int,
    showContextUsage: Boolean,
    isStreaming: Boolean,
    reasoningEffort: ReasoningEffort,
    availableReasoningEfforts: List<ReasoningEffort>,
    pendingImages: List<PendingImageUi>,
    pendingFileReferences: List<PendingFileReferenceUi>,
    queuedMessages: List<QueuedMessageUi>,
    inputSyncVersion: Int,
    focusInputOnSync: Boolean,
    messageEdit: MessageEditUiState?,
    onSubmit: (String) -> Unit,
    onInterruptAndSubmit: (String) -> Unit,
    onQueueMessage: (String) -> Unit,
    onSteerQueuedMessage: (String) -> Unit,
    onRecallQueuedMessage: (String, String) -> Unit,
    onRemoveQueuedMessage: (String) -> Unit,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    onCompactContext: () -> Unit,
    canCompactContext: Boolean,
    onStop: () -> Unit,
    isPaused: Boolean = false,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    onAttachImage: (String) -> Unit,
    onRemoveImage: (String) -> Unit,
    onAttachFiles: (List<String>) -> Unit,
    onAttachFolder: (String) -> Unit,
    onAttachFilePath: (String) -> Unit,
    onRemoveFileReference: (String) -> Unit,
    onCancelMessageEdit: () -> Unit,
    onOpenNavigator: () -> Unit,
    commands: List<AgentCommand> = emptyList(),
    commandConfirm: CommandConfirmUi? = null,
    onConfirmCommand: () -> Unit = {},
    onDismissCommandConfirm: () -> Unit = {},
    /** 命令结果卡：/status、/effort、错误提示，停留到用户关掉。 */
    commandResult: CommandResultUi? = null,
    onDismissCommandResult: () -> Unit = {},
    /** 命令主动展开的面板（/help、/skills、/model）。 */
    commandPalette: CommandPaletteUi? = null,
    onPickPaletteEntry: (AgentPaletteEntry) -> Unit = {},
    onCloseCommandPalette: () -> Unit = {},
    onLongPressPaletteEntry: (AgentPaletteEntry) -> Unit = {},
    inputResetToken: Long = 0,
    toolAccess: ToolAccessUiState = ToolAccessUiState(),
    referenceCandidates: List<MessageReferenceUi> = emptyList(),
    pendingMessageRefs: List<MessageReferenceUi> = emptyList(),
    onAttachReference: (MessageReferenceUi) -> Unit = {},
    onRemoveReference: (String) -> Unit = {},
    onRefreshToolAccess: () -> Unit = {},
    onToggleTool: (ToolToggleItem, Boolean) -> Unit = { _, _ -> },
) {
    val surface = MiuixTheme.colorScheme.surface
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .imePadding(),
    ) {
        // 底部以连续垂直渐隐承接悬浮输入胶囊，避免一整块不透明实心矩形把消息流生硬截断。
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to Color.Transparent,
                        0.26f to surface.copy(alpha = 0.86f),
                        0.55f to surface.copy(alpha = 0.98f),
                        1.0f to surface,
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 14.dp, start = 14.dp, end = 14.dp, bottom = 10.dp),
        ) {
            LocalProfileSwitchToast(modifier = Modifier.fillMaxWidth())
            AnimatedVisibility(
                visible = commandResult != null,
                enter = fadeIn(tween(140)) + expandVertically(tween(160)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
            ) {
                commandResult?.let { result ->
                    AgentCommandResultCard(
                        result = result,
                        onDismiss = onDismissCommandResult,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            AnimatedVisibility(
                visible = commandConfirm != null,
                enter = fadeIn(tween(140)) + expandVertically(tween(160)),
                exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
            ) {
                commandConfirm?.let { confirm ->
                    AgentCommandConfirmCard(
                        confirm = confirm,
                        onAccept = onConfirmCommand,
                        onDismiss = onDismissCommandConfirm,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
            }
            // Conversation lookup remains in the top-right menu; no bottom handle or spacer.
            AgentChatInputBar(
                input = input,
                draftKey = draftKey,
                modelPickerState = modelPickerState,
                onModelSelected = onModelSelected,
                onOpenModelProviders = onOpenModelProviders,
                isCompacting = isCompacting,
                contextUsage = contextUsage,
                streamingOutputTokensEstimate = streamingOutputTokensEstimate,
                showContextUsage = showContextUsage,
                isStreaming = isStreaming,
                reasoningEffort = reasoningEffort,
                availableReasoningEfforts = availableReasoningEfforts,
                pendingImages = pendingImages,
                pendingFileReferences = pendingFileReferences,
                queuedMessages = queuedMessages,
                inputSyncVersion = inputSyncVersion,
                focusInputOnSync = focusInputOnSync,
                isEditingMessage = messageEdit != null,
                editHasLaterTurns = messageEdit?.hasLaterTurns == true,
                preserveFollowingMessages = messageEdit?.preserveFollowingMessages == true,
                onSubmit = onSubmit,
                onInterruptAndSubmit = onInterruptAndSubmit,
            onQueueMessage = onQueueMessage,
                onSteerQueuedMessage = onSteerQueuedMessage,
                onRecallQueuedMessage = onRecallQueuedMessage,
                onRemoveQueuedMessage = onRemoveQueuedMessage,
                onReasoningEffortChange = onReasoningEffortChange,
                onCompactContext = onCompactContext,
                canCompactContext = canCompactContext,
                onStop = onStop,
            isPaused = isPaused,
            onPause = onPause,
            onResume = onResume,
                onAttachImage = onAttachImage,
                onRemoveImage = onRemoveImage,
                onAttachFiles = onAttachFiles,
                onAttachFolder = onAttachFolder,
                onAttachFilePath = onAttachFilePath,
                onRemoveFileReference = onRemoveFileReference,
                onCancelMessageEdit = onCancelMessageEdit,
                commands = commands,
                pinnedPalette = commandPalette,
                onPickPaletteEntry = onPickPaletteEntry,
                onClosePalette = onCloseCommandPalette,
                onLongPressPaletteEntry = onLongPressPaletteEntry,
                suppressPalette = commandResult != null,
                inputResetToken = inputResetToken,
                toolAccess = toolAccess,
                referenceCandidates = referenceCandidates,
                pendingMessageRefs = pendingMessageRefs,
                onAttachReference = onAttachReference,
                onRemoveReference = onRemoveReference,
                onRefreshToolAccess = onRefreshToolAccess,
                onToggleTool = onToggleTool,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val BOTTOM_FOLLOW_RESPONSE_SECONDS = 0.085f
private const val BOTTOM_FOLLOW_MAX_FRAME_SECONDS = 0.05f
private const val BOTTOM_FOLLOW_MAX_SPEED_DP_PER_SECOND = 720f
private const val BOTTOM_FOLLOW_MIN_STEP_PX = 0.5f
private const val BOTTOM_FOLLOW_SNAP_DISTANCE_PX = 0.75f
private const val EXPAND_REANCHOR_SUPPRESS_MS = 350L
private const val BOTTOM_SETTLE_TIMEOUT_MS = 600L

private class TimelineEntriesCache {
    // Do not collapse previously exposed work groups on close and move the reading anchor.
    var searchWasOpened = false
    var entries: List<AgentTimelineEntry> = emptyList()
}
/** 搜索命中高亮保持时长。 */
private const val SEARCH_HIGHLIGHT_DURATION_MS = 2400L

internal fun resolveKeepBottomAnchored(
    current: Boolean,
    isUserDragging: Boolean,
    isAtBottom: Boolean,
    suppressAutoReanchor: Boolean = false,
): Boolean = when {
    isUserDragging -> isAtBottom
    suppressAutoReanchor -> current
    isAtBottom -> true
    else -> current
}

internal fun resolveBottomFollowEnabled(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
    isBottomSettling: Boolean = false,
): Boolean = (isStreaming || isBottomSettling) && keepBottomAnchored && !isUserDragging

internal fun shouldRequestInitialBottom(
    isStreaming: Boolean,
    keepBottomAnchored: Boolean,
    isUserDragging: Boolean,
): Boolean = isStreaming && keepBottomAnchored && !isUserDragging

@Composable
private fun EmptyChatState(
    showSuggestions: Boolean,
    onSuggestionClick: (String) -> Unit,
    /** 有可用模型才显示建议词；没模型时先解决「发不出去」的问题。 */
    modelReady: Boolean,
    onOpenModelProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val character = LocalRoleplayChat.current
    val isCharacterConversation = character != null
    val suggestions = listOf(
        SuggestionItem(
            title = stringResource(R.string.ui_analyze_current_screen_ebf08f),
            icon = Icons.Rounded.DocumentScanner,
            prompt = stringResource(R.string.suggestion_analyze_screen_prompt),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_open_wechat_6b2c28),
            icon = Icons.Rounded.RocketLaunch,
            prompt = stringResource(R.string.suggestion_open_wechat_prompt),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_browse_the_web_da7afb),
            icon = Icons.Rounded.Language,
            prompt = stringResource(R.string.suggestion_browse_web_prompt),
        ),
        SuggestionItem(
            title = stringResource(R.string.ui_check_memory_pressure_2d9600),
            icon = Icons.Rounded.Terminal,
            prompt = stringResource(R.string.suggestion_memory_pressure_prompt),
        ),
    )

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 56.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (character != null) {
                CharacterAvatar(
                    name = character.characterName,
                    avatarPath = character.avatarPath,
                    size = 88.dp,
                    shape = CircleShape,
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = character.characterName,
                    style = MiuixTheme.textStyles.title2,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.chat_character_story_start),
                    style = MiuixTheme.textStyles.body2,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                Text(
                    text = if (modelReady) {
                        stringResource(R.string.ui_how_can_i_help_you_e75391)
                    } else {
                        "先接入一个模型"
                    },
                    style = MiuixTheme.textStyles.headline1,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            if (!modelReady) {
                ModelSetupCard(
                    onOpenModelProviders = onOpenModelProviders,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            AnimatedVisibility(
                visible = showSuggestions && !isCharacterConversation && modelReady,
                enter = fadeIn(
                    animationSpec = tween(durationMillis = 220)
                ) + slideInVertically(
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow,
                    ),
                    initialOffsetY = { it / 3 },
                ),
                exit = fadeOut(
                    animationSpec = tween(durationMillis = 130)
                ) + slideOutVertically(
                    animationSpec = tween(durationMillis = 180),
                    targetOffsetY = { it / 4 },
                ),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    suggestions.chunked(2).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            rowItems.forEach { item ->
                                SuggestionCard(
                                    item = item,
                                    onClick = { onSuggestionClick(item.prompt) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 空会话 + 没有可用模型时的引导卡。
 *
 * 为什么不用气泡 tour：配置模型要跨三层页面、还要填 Key，
 * 气泡挡着反而不好操作；这里直接把入口摆在首屏正中间，一步进目录。
 */
@Composable
private fun ModelSetupCard(
    onOpenModelProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 不用 EtaCard：它的 insideMargin 在这张「标题 + 两行说明 + 按钮」的卡上会让首行
    // 贴到圆角内沿，实测被白色圆角裁掉半行。这里改成自绘容器 + 显式 padding，
    // 内边距由 padding 保证，内容一定落在 surface 之内。
    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(color = MiuixTheme.colorScheme.surface, cornerRadius = 20.dp)
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.5f),
                cornerRadius = 20.dp,
            )
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(
            text = "还没有可用的模型",
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Text(
            text = "接入一个供应商和模型就能开始对话。目录里有一批现成配置，填个 Key 就行。",
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
        )
        UiCoreTextButton(
            text = "从目录添加",
            onClick = onOpenModelProviders,
        )
    }
}

@Composable
private fun SuggestionCard(
    item: SuggestionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isCoui = LocalUiStyle.current == AppearanceUiStyle.COUI
    val isDark = MiuixTheme.colorScheme.surface.luminance() < 0.5f
    val ink = MiuixTheme.colorScheme.onSurface.copy(alpha = 1f)
    val fill = ink.copy(alpha = if (isDark) 0.055f else 0.028f)
    val border = ink.copy(alpha = if (isDark) 0.10f else 0.065f)
    val interactionSource = remember { MutableInteractionSource() }
    val shapeModifier = if (isCoui) {
        val shape = CouiShape(15.dp, 2.2f)
        Modifier
            .couiButtonFeedback(isDark, interactionSource, enabled = true)
            .clip(shape)
            .background(fill, shape)
            .border(0.5.dp, border, shape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    } else {
        Modifier
            .squircleSurface(color = fill, cornerRadius = 15.dp)
            .squircleBorder(width = 0.5.dp, color = border, cornerRadius = 15.dp)
            .clickable(onClick = onClick)
    }
    Row(
        modifier = modifier
            .then(shapeModifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary.copy(alpha = if (isDark) 0.16f else 0.10f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = item.title,
            style = MiuixTheme.textStyles.body2.copy(
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            ),
            color = MiuixTheme.colorScheme.onSurface.copy(alpha = 0.88f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private data class SuggestionItem(
    val title: String,
    val icon: ImageVector,
    val prompt: String,
)
