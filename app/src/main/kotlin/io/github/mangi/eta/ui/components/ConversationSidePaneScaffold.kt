package io.github.mangi.eta.ui.components

import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import io.github.mangi.eta.R
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.theme.MiuixTheme

private object ConversationPaneMetrics {
    val PaneMaxWidth = 340.dp
    val PaneWidthFraction = 0.84f
    const val ChatScrimAlpha = 0.16f
    const val SettleDampingRatio = 1f
    const val SettleStiffness = 146f
    const val SettleVisibilityThresholdPx = 0.5f
}

@Composable
fun ConversationSidePaneScaffold(
    state: ConversationPaneUiState,
    visible: Boolean,
    backHandlerEnabled: Boolean,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onSearchChange: (String) -> Unit,
    onNewConversation: () -> Unit,
    onNewConversationInCategory: (String?) -> Unit = { _ -> onNewConversation() },
    onConversationSelected: (String) -> Unit,
    onConversationTogglePin: (ConversationSummaryUi) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onMoveToCategory: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onConversationPin: (ConversationSummaryUi) -> Unit,
    onBatchDelete: (List<ConversationSummaryUi>) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRootSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenTasks: () -> Unit,
    onCreateCategory: (String) -> Unit,
    onRenameCategory: (String, String) -> Unit,
    onMoveCategory: (String, Int) -> Unit,
    onDeleteCategoryConfirmed: (String) -> Unit,
    /** 拖放落点：目标分组 id，null 表示移出分组。 */
    onDropConversationToCategory: ((ConversationSummaryUi, String?) -> Unit)? = null,
    modifier: Modifier = Modifier,
    activeModelLabel: String? = null,
    hasPermissionAttention: Boolean = false,
    workspaceVisible: Boolean = false,
    onWorkspaceOpen: () -> Unit = {},
    onWorkspaceDismiss: () -> Unit = {},
    workspaceContent: (@Composable () -> Unit)? = null,
    edgeWidthDp: Int = io.github.mangi.eta.data.model.DEFAULT_CONVERSATION_PANE_EDGE_WIDTH_DP,
    gestureSensitivity: Float = io.github.mangi.eta.data.model.DEFAULT_GESTURE_SENSITIVITY,
    commitThresholdPercent: Float = io.github.mangi.eta.data.model.DEFAULT_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT,
    content: @Composable () -> Unit,
) {
    val sceneLifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val navigationEventState = rememberNavigationEventState(NavigationEventInfo.None)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val selectionLock = remember { ChatSelectionLock() }
        val horizontalScrollLock = remember { ChatHorizontalScrollLock() }
        val density = LocalDensity.current
        val screenWidthDp = maxWidth.value.toInt()
        val effectiveGestureStartWidthDp = ConversationPaneEdgeWidthPolicy.effectiveWidth(edgeWidthDp, screenWidthDp)
        val gestureStartWidth = with(density) { effectiveGestureStartWidthDp.dp.toPx() }
        val sensitivity by rememberUpdatedState(gestureSensitivity)
        val currentCommitThreshold by rememberUpdatedState(commitThresholdPercent)
        val paneWidth = minOf(maxWidth * ConversationPaneMetrics.PaneWidthFraction, ConversationPaneMetrics.PaneMaxWidth)
        val paneWidthPx = with(density) { paneWidth.toPx() }
        val anchors = remember(paneWidthPx) {
            DraggableAnchors {
                ConversationPaneAnchor.Closed at 0f
                ConversationPaneAnchor.Open at paneWidthPx
            }
        }
        val paneDragState = remember {
            AnchoredDraggableState(
                initialValue = if (visible) ConversationPaneAnchor.Open else ConversationPaneAnchor.Closed,
                anchors = anchors,
            )
        }
        val settleAnimation = remember {
            spring<Float>(
                dampingRatio = ConversationPaneMetrics.SettleDampingRatio,
                stiffness = ConversationPaneMetrics.SettleStiffness,
                visibilityThreshold = ConversationPaneMetrics.SettleVisibilityThresholdPx,
            )
        }
        val currentVisible by rememberUpdatedState(visible)
        val currentOnOpen by rememberUpdatedState(onOpen)
        val currentOnDismiss by rememberUpdatedState(onDismiss)
        val hapticFeedback = LocalHapticFeedback.current
        val currentHapticFeedback by rememberUpdatedState(hapticFeedback)
        val dragInteractions = remember { MutableInteractionSource() }
        val isDragging by dragInteractions.collectIsDraggedAsState()
        val isRevealed by remember(paneDragState) {
            derivedStateOf {
                val offset = paneDragState.offset
                !offset.isNaN() && offset > 0.5f
            }
        }
        val panelListState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val focusManager = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        val closePane: () -> Unit = {
            currentHapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
            focusManager.clearFocus()
            keyboard?.hide()
            if (currentVisible) {
                currentOnDismiss()
            } else {
                scope.launch { paneDragState.animateTo(ConversationPaneAnchor.Closed, settleAnimation) }
            }
        }
        LaunchedEffect(isRevealed) {
            if (isRevealed) {
                focusManager.clearFocus()
                keyboard?.hide()
            }
        }

        SideEffect {
            paneDragState.updateAnchors(anchors)
        }

        LaunchedEffect(visible, paneWidthPx) {
            if (!visible && isRevealed) {
                focusManager.clearFocus()
                keyboard?.hide()
            }
            val target = if (visible) ConversationPaneAnchor.Open else ConversationPaneAnchor.Closed
            if (paneDragState.targetValue != target || paneDragState.settledValue != target) {
                paneDragState.animateTo(target, settleAnimation)
            }
        }

        LaunchedEffect(paneDragState) {
            conversationPaneSettledValues(paneDragState) { isDragging }.collectLatest { settledValue ->
                val settledOpen = settledValue == ConversationPaneAnchor.Open
                if (settledOpen != currentVisible) {
                    if (settledOpen) {
                        currentOnOpen()
                    } else {
                        focusManager.clearFocus()
                        keyboard?.hide()
                        currentOnDismiss()
                    }
                }
            }
        }

        // NavDisplay 的退出条目在转场期间仍会保留组合；仅允许已稳定显示的首页
        // 处理侧栏返回，避免它抢先消费二级页面的第一次返回事件。
        NavigationBackHandler(
            state = navigationEventState,
            isBackEnabled = (isRevealed || workspaceVisible) &&
                backHandlerEnabled &&
                sceneLifecycleState == Lifecycle.State.RESUMED,
            onBackCompleted = { if (workspaceVisible) onWorkspaceDismiss() else closePane() },
        )

        suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitHorizontalIntentOrCancellation(
            pointerId: androidx.compose.ui.input.pointer.PointerId,
            touchSlop: Float,
            onTouchSlopReached: (androidx.compose.ui.input.pointer.PointerInputChange, Float) -> Unit,
        ): androidx.compose.ui.input.pointer.PointerInputChange? {
            var totalX = 0f
            var totalY = 0f
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == pointerId } ?: return null
                if (!change.pressed) return null
                val delta = change.positionChangeIgnoreConsumed()
                totalX += delta.x
                totalY += delta.y
                if (ChatGesturePolicy.shouldClaimHorizontal(totalX, totalY, touchSlop)) {
                    onTouchSlopReached(change, totalX)
                    return change
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxSize()
                .clipToBounds()
                .background(conversationPaneContainerColor())
                // Full content width can start a horizontal history swipe. Native slop arbitration
                // leaves child text selection and vertical scrolling intact until horizontal intent wins.
                .pointerInput(paneDragState, paneWidthPx, gestureStartWidth, backHandlerEnabled, sceneLifecycleState, workspaceVisible) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                        val openAtStart = currentVisible || paneDragState.offset > 0.5f
                        // A Markdown table/code scroller owns this pointer sequence. Never ask the
                        // drawer to "help" at its edge; this removes the first-right-swipe trap.
                        if (horizontalScrollLock.active) return@awaitEachGesture
                        if (!ChatGesturePolicy.canStart(down.position.x, gestureStartWidth, openAtStart,
                                selectionLock.active, workspaceVisible,
                                backHandlerEnabled && sceneLifecycleState == Lifecycle.State.RESUMED)) return@awaitEachGesture
                        var initialDelta = 0f
                        var acceptedHorizontalIntent = false
                        val touchSlop = viewConfiguration.touchSlop
                        val slop = awaitHorizontalIntentOrCancellation(down.id, touchSlop) { change, over ->
                            if (!selectionLock.active && ChatGesturePolicy.acceptsInitialDelta(over, openAtStart)) {
                                acceptedHorizontalIntent = true
                                initialDelta = ChatGesturePolicy.scaledDragDelta(over, sensitivity)
                                change.consume()
                            }
                        } ?: return@awaitEachGesture
                        if (!acceptedHorizontalIntent || selectionLock.active) return@awaitEachGesture
                        val interaction = DragInteraction.Start()
                        dragInteractions.tryEmit(interaction)
                        var thresholdReached = false
                        fun maybeCueCommit(previousOffset: Float, currentOffset: Float) {
                            if (!thresholdReached && ChatGesturePolicy.crossedCommitThreshold(
                                    previousOffset = previousOffset,
                                    currentOffset = currentOffset,
                                    width = paneWidthPx,
                                    panelWasOpen = openAtStart,
                                    commitThresholdPercent = currentCommitThreshold,
                                )
                            ) {
                                thresholdReached = true
                                currentHapticFeedback.performHapticFeedback(HapticFeedbackType.GestureEnd)
                            }
                        }
                        var previousOffset = paneDragState.offset
                        paneDragState.dispatchRawDelta(initialDelta)
                        maybeCueCommit(previousOffset, paneDragState.offset)
                        var completed = true
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == slop.id }
                            if (change == null || !change.pressed) break
                            if (selectionLock.active) {
                                completed = false
                                break
                            }
                            val delta = change.positionChangeIgnoreConsumed()
                            if (delta.x != 0f) {
                                previousOffset = paneDragState.offset
                                paneDragState.dispatchRawDelta(
                                    ChatGesturePolicy.scaledDragDelta(delta.x, sensitivity),
                                )
                                maybeCueCommit(previousOffset, paneDragState.offset)
                                change.consume()
                            }
                        }
                        dragInteractions.tryEmit(if (completed && !selectionLock.active)
                            DragInteraction.Stop(interaction) else DragInteraction.Cancel(interaction))
                        val commit = completed && !selectionLock.active && ChatGesturePolicy.isBeyondCommitThreshold(
                            offset = paneDragState.offset,
                            width = paneWidthPx,
                            panelWasOpen = openAtStart,
                            commitThresholdPercent = currentCommitThreshold,
                        )
                        val target = if (commit) !openAtStart else openAtStart
                        val targetAnchor = if (target) ConversationPaneAnchor.Open else ConversationPaneAnchor.Closed
                        scope.launch { paneDragState.animateTo(targetAnchor, settleAnimation) }
                    }
                },
            contentAlignment = AbsoluteAlignment.TopLeft,
        ) {
            // 两个槽位并排测量和放置，由同一位移推移；不在聊天内容下面叠放侧栏。
            // 侧栏保留组合，避免每次起手重新创建列表和搜索框。
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    ConversationPanePanel(
                        state = state,
                        width = paneWidth,
                        listState = panelListState,
                        isRevealed = isRevealed,
                        activeModelLabel = activeModelLabel,
                        hasPermissionAttention = hasPermissionAttention,
                        onSearchChange = onSearchChange,
                        onNewConversation = onNewConversation,
                        onNewConversationInCategory = onNewConversationInCategory,
                        onConversationSelected = onConversationSelected,
                        onConversationTogglePin = onConversationTogglePin,
                        onConversationRename = onConversationRename,
                        onMoveToCategory = onMoveToCategory,
                        onDropConversationToCategory = onDropConversationToCategory,
                        onConversationExport = onConversationExport,
                        onConversationDelete = onConversationDelete,
                        onConversationPin = onConversationPin,
                        onBatchDelete = onBatchDelete,
                        onOpenSettings = onOpenSettings,
                        onOpenRootSettings = onOpenRootSettings,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onOpenModelProviders = onOpenModelProviders,
                        onOpenTools = onOpenTools,
                        onOpenSkills = onOpenSkills,
                        onOpenCharacters = onOpenCharacters,
                        onOpenPermissions = onOpenPermissions,
                        onOpenTasks = onOpenTasks,
                        onCreateCategory = onCreateCategory,
                        onRenameCategory = onRenameCategory,
                        onMoveCategory = onMoveCategory,
                        onDeleteCategoryConfirmed = onDeleteCategoryConfirmed,
                        modifier = Modifier
                            .focusProperties { onEnter = { if (!isRevealed) cancelFocusChange() } }
                            .focusGroup()
                            .then(if (!isRevealed) Modifier.clearAndSetSemantics {} else Modifier),
                    )
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .drawWithContent {
                                drawContent()
                                val progress = conversationPaneMotion(paneDragState.offset, paneWidthPx).progress
                                drawRect(Color.Black, alpha = ConversationPaneMetrics.ChatScrimAlpha * progress)
                            }
                            .background(MiuixTheme.colorScheme.background),
                    ) {
                        Box(
                            modifier = Modifier
                                .focusProperties {
                                    onEnter = { if (isRevealed) cancelFocusChange() }
                                }
                                .focusGroup()
                                .then(if (isRevealed) Modifier.clearAndSetSemantics {} else Modifier),
                        ) {
                            CompositionLocalProvider(
                                LocalChatSelectionLock provides selectionLock,
                                LocalChatHorizontalScrollLock provides horizontalScrollLock,
                            ) { content() }
                        }
                        if (isRevealed) {
                            Box(
                                modifier = Modifier.fillMaxSize().clickable(
                                    interactionSource = null,
                                    indication = null,
                                    onClickLabel = stringResource(R.string.action_close),
                                    onClick = closePane,
                                ),
                            )
                        }
                    }
                },
            ) { measurables, constraints ->
                val panel = measurables[0].measure(
                    Constraints.fixed(paneWidthPx.roundToInt(), constraints.maxHeight),
                )
                val page = measurables[1].measure(
                    Constraints.fixed(constraints.maxWidth, constraints.maxHeight),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    // 物理左侧是会话区；不随 RTL 改变两个槽位的关系。
                    val motion = conversationPaneMotion(paneDragState.offset, paneWidthPx)
                    val offset = motion.foregroundOffset.roundToInt()
                    panel.placeWithLayer(offset - panel.width, 0)
                    page.placeWithLayer(offset, 0)
                }
            }

            // The existing menu controls workspace visibility. No swipe or negative offset is involved.
            if (workspaceVisible && workspaceContent != null) {
                Box(Modifier.fillMaxSize()) { workspaceContent() }
            }

        }
    }
}
