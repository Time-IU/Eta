package io.github.mangi.eta.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.itos.coui.layout.CouiPageDefaults
import com.itos.coui.layout.Scaffold as CouiLayoutScaffold
import com.itos.coui.menu.CouiMenuItem
import com.itos.coui.menu.Menu
import com.itos.coui.menu.couiMenuAnchor
import com.itos.coui.menu.rememberCouiMenuAnchor
import com.itos.coui.theme.CouiTheme
import com.itos.coui.theme.LocalCouiTextStyle
import com.itos.coui.topappbar.CouiTopAppBarDefaults
import com.itos.coui.topappbar.TopAppBar as CouiTopAppBar
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.AppearanceUiStyle
import io.github.mangi.eta.data.model.TtsProvider
import io.github.mangi.eta.data.repository.SpeechSettingsRepository
import io.github.mangi.eta.ui.components.AdaptiveTopAppBar
import io.github.mangi.eta.ui.components.AgentTopBarChatHeader
import io.github.mangi.eta.ui.components.ChatActionIcons
import io.github.mangi.eta.ui.components.ChatImagePreviewHost
import io.github.mangi.eta.ui.components.ConversationSidePaneScaffold
import io.github.mangi.eta.ui.components.MiuixBackButton
import io.github.mangi.eta.ui.components.RoleplayChatUi
import io.github.mangi.eta.agent.model.LocalToolProfileStore
import io.github.mangi.eta.ui.components.WorkspaceShiftHost
import io.github.mangi.eta.ui.components.CharacterAvatar
import io.github.mangi.eta.ui.components.ShortcutIcon
import io.github.mangi.eta.ui.components.openVirtualScreen
import io.github.mangi.eta.ui.components.rememberVirtualScreenMenuEntries
import io.github.mangi.eta.ui.components.TopBarBackdrop
import io.github.mangi.eta.ui.components.captureForTopBar
import io.github.mangi.eta.ui.components.coui.CouiBackButton
import io.github.mangi.eta.ui.components.coui.CouiTopAppBarIconButton
import io.github.mangi.eta.ui.components.rememberTopBarBackdrop
import io.github.mangi.eta.ui.components.topBarContainerColor
import io.github.mangi.eta.ui.model.AgentModelPickerUiState
import io.github.mangi.eta.ui.model.ConversationPaneUiState
import io.github.mangi.eta.ui.model.ConversationSummaryUi
import io.github.mangi.eta.ui.navigation.AppRoute
import kotlinx.coroutines.launch
import io.github.mangi.eta.ui.onboarding.OnboardingHost
import io.github.mangi.eta.ui.onboarding.SurfaceKey
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowListPopup
import io.github.mangi.eta.ui.voice.SpeechAutoReadToggle

/**
 * Agent App 统一壳层。
 *
 * - 负责全局 Scaffold、状态栏/横向安全边距、顶层工具栏。
 * - 首页工具栏只保留历史入口与溢出菜单，保持聊天舞台干净。
 * - 非首页子路由统一提供返回按钮与标题，避免每个页面各自像独立设置页。
 * - Settings 由标准二级页骨架自己提供 TopAppBar，壳层在此路由不重复绘制。
 */
@Composable
internal fun AgentAppShell(
    currentRoute: AppRoute?,
    isCurrentRoute: Boolean,
    conversationPaneState: ConversationPaneUiState?,
    isConversationPaneOpen: Boolean,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onDismissConversationPane: () -> Unit,
    onSearchConversations: (String) -> Unit,
    onNewConversation: () -> Unit,
    onOpenChatSearch: () -> Unit,
    onNewConversationInCategory: (String?) -> Unit = { _ -> onNewConversation() },
    onOpenTerminal: () -> Unit,
    onOpenBrowser: () -> Unit,
    /** 打开分支图：会话页 ⋯ 菜单里的入口。 */
    onOpenBranchGraph: () -> Unit = {},
    onSelectConversation: (String) -> Unit,
    onConversationTogglePin: (ConversationSummaryUi) -> Unit,
    onConversationRename: (ConversationSummaryUi) -> Unit,
    onMoveToCategory: (ConversationSummaryUi) -> Unit,
    onConversationExport: (ConversationSummaryUi) -> Unit,
    onConversationDelete: (ConversationSummaryUi) -> Unit,
    onConversationPin: (ConversationSummaryUi) -> Unit,
    onBatchDelete: (List<ConversationSummaryUi>) -> Unit,
    onOpenTools: () -> Unit,
    onOpenSkills: () -> Unit,
    onOpenCharacters: () -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenRootSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenModelProviders: () -> Unit,
    onOpenTasks: () -> Unit,
    onCreateCategory: (String) -> Unit,
    onRenameCategory: (String, String) -> Unit,
    onMoveCategory: (String, Int) -> Unit,
    onDeleteCategoryConfirmed: (String) -> Unit,
    conversationStatus: (@Composable () -> Unit)? = null,
    /** 工作区面板打开时，只把顶栏以下的内容左移，顶栏那一行保持原样。 */
    workspaceShifted: Boolean = false,
    onDropConversationToCategory: ((io.github.mangi.eta.ui.model.ConversationSummaryUi, String?) -> Unit)? = null,
    workspaceVisible: Boolean = false,
    onWorkspaceOpen: () -> Unit = {},
    onWorkspaceDismiss: () -> Unit = {},
    workspaceContent: (@Composable () -> Unit)? = null,
    edgeWidthDp: Int = io.github.mangi.eta.data.model.DEFAULT_CONVERSATION_PANE_EDGE_WIDTH_DP,
    gestureSensitivity: Float = io.github.mangi.eta.data.model.DEFAULT_GESTURE_SENSITIVITY,
    commitThresholdPercent: Float = io.github.mangi.eta.data.model.DEFAULT_CONVERSATION_PANE_COMMIT_THRESHOLD_PERCENT,
    /** 顶栏放不下的低频状态提示，改在 ⋯ 菜单里以文字列出。 */
    conversationTrimmed: Pair<Int, Int>? = null,
    /** 会话查找是否打开：有面板时隐藏其它手势教学。 */
    navigatorOpen: Boolean = false,
    modifier: Modifier = Modifier,
    homeTitle: String? = null,
    homeCharacter: RoleplayChatUi? = null,
    homeModelPickerState: AgentModelPickerUiState? = null,
    isHomeStreaming: Boolean = false,
    hasPermissionAttention: Boolean = false,
    onHomeModelSelected: (String) -> Unit = {},
    onOpenCharacter: () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val uiStyle = LocalUiStyle.current
    val pageContent: @Composable () -> Unit = {
        when (uiStyle) {
            AppearanceUiStyle.COUI -> {
                val dark = CouiTheme.isDark
                val colors = CouiPageDefaults.themeColors(dark)
                val isHome = currentRoute is AppRoute.Home
                // COUI needs the same real MIUIX overlay registry as the MIUIX branch.
                // Zero-inset host only: COUI retains all top-bar and safe-area ownership.
                Scaffold(contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp)) { _ ->
                CouiLayoutScaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MiuixTheme.colorScheme.surface,
                    windowInsets = WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                    topBar = {
                        if (currentRoute !is AppRoute.Settings) {
                            CouiTopAppBar(
                                title = {
                                    if (isHome && homeModelPickerState != null) {
                                        Box(
                                            modifier = Modifier.fillMaxWidth(),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            AgentTopBarChatHeader(
                                                conversationTitle = homeTitle,
                                                character = homeCharacter,
                                                state = homeModelPickerState,
                                                isStreaming = isHomeStreaming,
                                                onModelSelected = onHomeModelSelected,
                                                onOpenCharacter = onOpenCharacter,
                                            )
                                        }
                                    } else {
                                        BasicText(
                                            text = titleForRoute(currentRoute),
                                            style = LocalCouiTextStyle.current,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                },
                                navigationIcon = {
                                    if (isHome) {
                                        ChatTopBarMenuButton(
                                            icon = Icons.AutoMirrored.Outlined.ShortText,
                                            contentDescription = stringResource(R.string.action_conversation_history),
                                            onClick = onOpenConversationPane,
                                            tint = colors.title,
                                        )
                                    } else {
                                        CouiBackButton(onClick = onBack, dark = dark, colors = colors)
                                    }
                                },
                                actions = {
                                    if (isHome) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            conversationStatus?.invoke()
                                            CouiTopBarOverflowMenu(
                                                dark = dark,
                                                colors = colors,
                                                onNewConversation = onNewConversation,
                                                onOpenChatSearch = onOpenChatSearch,
                                                onOpenWorkspaceMenu = onWorkspaceOpen,
                                                onOpenTerminal = onOpenTerminal,
                                                onOpenBrowser = onOpenBrowser,
                                                onOpenBranchGraph = onOpenBranchGraph,
                                            )
                                        }
                                    }
                                },
                                dark = dark,
                                colors = CouiTopAppBarDefaults.colors(
                                    backgroundColor = MiuixTheme.colorScheme.surface,
                                    titleColor = colors.title,
                                    dark = dark,
                                ),
                            )
                        }
                    },
                ) { padding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        WorkspaceShiftHost(shifted = workspaceShifted) { content(padding) }
                    }
                }
            }
            }
            AppearanceUiStyle.MIUIX -> {
                // 首页顶栏高度固定，不参与折叠；未绑定顶栏的滚动行为会吞掉聊天列表的单向滑动。
                val scrollBehavior = if (currentRoute is AppRoute.Home) null else MiuixScrollBehavior()
                val backdrop = rememberTopBarBackdrop()
                val topBarColor = topBarContainerColor(backdrop)
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    contentWindowInsets = WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                    topBar = {
                        if (currentRoute !is AppRoute.Settings) {
                            TopBarBackdrop(backdrop) {
                                AgentTopBar(
                                    route = currentRoute,
                                    scrollBehavior = scrollBehavior,
                                    color = topBarColor,
                                    onBack = onBack,
                                    onOpenConversationPane = onOpenConversationPane,
                                    onNewConversation = onNewConversation,
                                    onOpenChatSearch = onOpenChatSearch,
                                                onOpenWorkspaceMenu = onWorkspaceOpen,
                                    onOpenTerminal = onOpenTerminal,
                                    onOpenBrowser = onOpenBrowser,
                                    conversationStatus = conversationStatus,
                                    conversationTrimmed = conversationTrimmed,
                                    onOpenBranchGraph = onOpenBranchGraph,
                                    homeTitle = homeTitle,
                                    homeCharacter = homeCharacter,
                                    homeModelPickerState = homeModelPickerState,
                                    isHomeStreaming = isHomeStreaming,
                                    onHomeModelSelected = onHomeModelSelected,
                                    onOpenCharacter = onOpenCharacter,
                                )
                            }
                        }
                    },
                ) { padding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .captureForTopBar(backdrop)
                            .then(
                                if (scrollBehavior != null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
                                else Modifier,
                            ),
                    ) {
                        WorkspaceShiftHost(shifted = workspaceShifted) { content(padding) }
                    }
                }
            }
        }
    }

    ChatImagePreviewHost(modifier = modifier) {
        if (conversationPaneState != null && currentRoute is AppRoute.Home) {
            ConversationSidePaneScaffold(
                state = conversationPaneState,
                visible = isConversationPaneOpen,
                backHandlerEnabled = isCurrentRoute,
                onOpen = onOpenConversationPane,
                onDismiss = onDismissConversationPane,
                onSearchChange = onSearchConversations,
                onNewConversation = onNewConversation,
                onConversationSelected = onSelectConversation,
                onConversationTogglePin = onConversationTogglePin,
                onConversationRename = onConversationRename,
                onMoveToCategory = onMoveToCategory,
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
                activeModelLabel = homeModelPickerState?.selectedModel?.displayName,
                hasPermissionAttention = hasPermissionAttention,
                onOpenTasks = onOpenTasks,
                onCreateCategory = onCreateCategory,
                onNewConversationInCategory = onNewConversationInCategory,
                onRenameCategory = onRenameCategory,
                onMoveCategory = onMoveCategory,
                onDeleteCategoryConfirmed = onDeleteCategoryConfirmed,
                onDropConversationToCategory = onDropConversationToCategory,
                workspaceVisible = workspaceVisible,
                onWorkspaceOpen = onWorkspaceOpen,
                onWorkspaceDismiss = onWorkspaceDismiss,
                workspaceContent = workspaceContent,
                edgeWidthDp = edgeWidthDp,
                gestureSensitivity = gestureSensitivity,
                commitThresholdPercent = commitThresholdPercent,
            ) {
                pageContent()
            }
        } else {
            pageContent()
        }

        // 新手引导层：必须在 Box 的最末尾，才能盖住页面、侧栏与工作区。
        // Dialog 是独立 window，天然在这层之上，不需要额外处理。
        if (isCurrentRoute) OnboardingHost(
            currentRoute = currentRoute,
            surface = when {
                isConversationPaneOpen -> SurfaceKey.Sidebar
                workspaceVisible -> SurfaceKey.Workspace
                else -> null
            },
            navigatorOpen = navigatorOpen,
        )
    }
}

@Composable
private fun CouiTopBarOverflowMenu(
    dark: Boolean,
    colors: com.itos.coui.layout.CouiPageColors,
    onNewConversation: () -> Unit,
    onOpenChatSearch: () -> Unit,
    onOpenWorkspaceMenu: () -> Unit = {},
    onOpenTerminal: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenBranchGraph: () -> Unit = {},
) {
    var showMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val speechSettings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
    val hasSpeechPlayback = speechSettings?.let { it.tts != TtsProvider.NONE } == true
    val autoReadEnabled = speechSettings?.chatAutoSpeak == true
    val anchor = rememberCouiMenuAnchor()

    val newConversationText = stringResource(R.string.action_new_conversation)
    val openTerminalText = stringResource(R.string.action_open_terminal)
    val context = LocalContext.current
    val virtualEntries = rememberVirtualScreenMenuEntries()
    val searchText = io.github.mangi.eta.ui.components.conversationLookupTitle()
    val workspaceText = stringResource(R.string.capability_workspace)
    val branchText = stringResource(R.string.action_branch_graph)
    val openBrowserText = stringResource(R.string.action_open_browser)
    val autoReadText = stringResource(
        if (autoReadEnabled) R.string.speech_disable_auto_read else R.string.speech_enable_auto_read,
    )
    val menuItems = remember(virtualEntries, branchText, searchText, workspaceText,
        newConversationText,
        openTerminalText,
        openBrowserText,
        autoReadText,
        hasSpeechPlayback,
        autoReadEnabled,
    ) {
        buildList {
            add(CouiMenuItem(value = 5, text = searchText, icon = Icons.Rounded.Search))
            add(CouiMenuItem(value = 6, text = workspaceText, icon = Icons.Rounded.Folder))
            add(CouiMenuItem(value = 0, text = newConversationText, icon = ChatActionIcons.NewChat))
            add(CouiMenuItem(value = 1, text = openTerminalText, icon = Icons.Rounded.Terminal))
            add(CouiMenuItem(value = 2, text = openBrowserText, icon = Icons.Rounded.Language))
            add(CouiMenuItem(value = 4, text = branchText, icon = Icons.Rounded.AccountTree))
            virtualEntries.forEachIndexed { index, entry -> add(CouiMenuItem(value = 100 + index, text = entry.label)) }
            if (hasSpeechPlayback) {
                add(
                    CouiMenuItem(
                        value = 3,
                        text = autoReadText,
                        icon = if (autoReadEnabled) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                    ),
                )
            }
        }
    }

    Box {
        ChatTopBarMenuButton(
            icon = Icons.Outlined.MoreHoriz,
            contentDescription = stringResource(R.string.action_more),
            modifier = Modifier.couiMenuAnchor(anchor),
            tint = colors.title,
            onClick = {
                anchor.open { showMenu = true }
            },
        )
        Menu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            items = menuItems,
            onItemClick = { item ->
                showMenu = false
                when (item.value) {
                    5 -> onOpenChatSearch()
                    6 -> onOpenWorkspaceMenu()
                    0 -> onNewConversation()
                    1 -> onOpenTerminal()
                    2 -> onOpenBrowser()
                    4 -> onOpenBranchGraph()
                    3 -> speechSettings?.let { current ->
                        scope.launch {
                            SpeechSettingsRepository.save(current.copy(chatAutoSpeak = !current.chatAutoSpeak))
                        }
                    }
                    else -> virtualEntries.getOrNull(item.value - 100)?.let { entry -> openVirtualScreen(context, scope, entry.component) }
                }
            },
            anchor = anchor,
        )
    }
}

@Composable
private fun AgentTopBar(
    route: AppRoute?,
    scrollBehavior: ScrollBehavior?,
    color: Color,
    onBack: () -> Unit,
    onOpenConversationPane: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenChatSearch: () -> Unit,
    onOpenWorkspaceMenu: () -> Unit = {},
    onOpenTerminal: () -> Unit,
    onOpenBrowser: () -> Unit,
    conversationStatus: (@Composable () -> Unit)? = null,
    conversationTrimmed: Pair<Int, Int>? = null,
    onOpenBranchGraph: () -> Unit = {},
    homeTitle: String?,
    homeCharacter: RoleplayChatUi?,
    homeModelPickerState: AgentModelPickerUiState?,
    isHomeStreaming: Boolean,
    onHomeModelSelected: (String) -> Unit,
    onOpenCharacter: () -> Unit,
) {
    val isHome = route is AppRoute.Home
    if (isHome && homeModelPickerState != null) {
        HomeMiuixTopBar(
            color = color,
            onOpenConversationPane = onOpenConversationPane,
            onNewConversation = onNewConversation,
            onOpenChatSearch = onOpenChatSearch,
            onOpenWorkspaceMenu = onOpenWorkspaceMenu,
            onOpenTerminal = onOpenTerminal,
            onOpenBrowser = onOpenBrowser,
            onOpenBranchGraph = onOpenBranchGraph,
            conversationTrimmed = conversationTrimmed,
            conversationStatus = conversationStatus,
        ) {
            AgentTopBarChatHeader(
                conversationTitle = homeTitle,
                character = homeCharacter,
                state = homeModelPickerState,
                isStreaming = isHomeStreaming,
                onModelSelected = onHomeModelSelected,
                onOpenCharacter = onOpenCharacter,
            )
        }
        return
    }

    val navigationIcon: @Composable () -> Unit = {
        MiuixBackButton(onClick = onBack)
    }
    val actions: @Composable RowScope.() -> Unit = {}

    if (route is AppRoute.Terminal) {
        SmallTopAppBar(
            title = titleForRoute(route),
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
            // miuix 的 actions 行本身是 Arrangement.End（靠右），右边原来有 16dp 默认留白，
            // 收到 6dp：胶囊组、＋、⋯ 一起往右贴，行尾也不空。
            actionIconPadding = 6.dp,
        )
    } else {
        AdaptiveTopAppBar(
            title = titleForRoute(route),
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
        )
    }
}

@Composable
private fun HomeMiuixTopBar(
    color: Color,
    onOpenConversationPane: () -> Unit,
    onNewConversation: () -> Unit,
    onOpenChatSearch: () -> Unit,
    onOpenWorkspaceMenu: () -> Unit = {},
    onOpenTerminal: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenBranchGraph: () -> Unit = {},
    conversationTrimmed: Pair<Int, Int>? = null,
    conversationStatus: (@Composable () -> Unit)? = null,
    centerContent: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(color)
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal))
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
            .height(56.dp)
            .clipToBounds()
            .pointerInput(Unit) {
                detectTapGestures { }
            }
            .padding(
                start = TopAppBarDefaults.NavigationIconPadding,
                end = TopAppBarDefaults.ActionIconPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatTopBarMenuButton(
            icon = Icons.AutoMirrored.Outlined.ShortText,
            contentDescription = stringResource(R.string.action_conversation_history),
            onClick = onOpenConversationPane,
        )
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            centerContent()
        }
        conversationStatus?.invoke()
        TopBarOverflowMenu(
            onNewConversation = onNewConversation,
            onOpenChatSearch = onOpenChatSearch,
            onOpenWorkspaceMenu = onOpenWorkspaceMenu,
            onOpenTerminal = onOpenTerminal,
            onOpenBrowser = onOpenBrowser,
            onOpenBranchGraph = onOpenBranchGraph,
            conversationTrimmed = conversationTrimmed,
        )
    }
}

private val TopBarMenuIconSize = 20.dp

/** 首页自绘顶栏的高度（与 miuix 紧凑顶栏一致）。 */
private val TopBarRowHeight = 56.dp

/**
 * 首页顶栏溢出菜单。WindowListPopup 以父布局为锚点，因此与触发按钮包在同一个 Box 中，
 * 弹层从按钮下方右对齐展开。
 */
@Composable
private fun TopBarOverflowMenu(
    onNewConversation: () -> Unit,
    onOpenChatSearch: () -> Unit,
    onOpenWorkspaceMenu: () -> Unit = {},
    onOpenTerminal: () -> Unit,
    onOpenBrowser: () -> Unit,
    onOpenBranchGraph: () -> Unit = {},
    conversationTrimmed: Pair<Int, Int>? = null,
) {
    var showMenu by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val speechSettings by remember { SpeechSettingsRepository.settingsFlow() }.collectAsState(initial = null)
    val virtualEntries = rememberVirtualScreenMenuEntries()
    Box {
        ChatTopBarMenuButton(
            icon = Icons.Outlined.MoreHoriz,
            contentDescription = stringResource(R.string.action_more),
            onClick = { showMenu = true },
        )
        WindowListPopup(show = showMenu, alignment = PopupPositionProvider.Align.End, onDismissRequest = { showMenu = false }) {
            val rows = buildList<Pair<DropdownItem, () -> Unit>> {
                add(DropdownItem(text = stringResource(R.string.capability_workspace), icon = { modifier -> Icon(Icons.Rounded.Folder, null, modifier.size(TopBarMenuIconSize)) }) to onOpenWorkspaceMenu)
                add(DropdownItem(text = io.github.mangi.eta.ui.components.conversationLookupTitle(), icon = { modifier -> Icon(Icons.Rounded.Search, null, modifier.size(TopBarMenuIconSize)) }) to onOpenChatSearch)
                add(DropdownItem(text = stringResource(R.string.action_new_conversation), icon = { modifier -> Icon(ChatActionIcons.NewChat, null, modifier.size(TopBarMenuIconSize)) }) to onNewConversation)
                virtualEntries.forEach { entry ->
                    add(DropdownItem(text = entry.label) to { openVirtualScreen(context, scope, entry.component) })
                }
                add(DropdownItem(text = stringResource(R.string.action_open_terminal), icon = { modifier -> Icon(Icons.Rounded.Terminal, null, modifier.size(TopBarMenuIconSize)) }) to onOpenTerminal)
                add(DropdownItem(text = stringResource(R.string.action_open_browser), icon = { modifier -> Icon(Icons.Rounded.Language, null, modifier.size(TopBarMenuIconSize)) }) to onOpenBrowser)
                add(DropdownItem(text = stringResource(R.string.action_branch_graph), icon = { modifier -> Icon(Icons.Rounded.AccountTree, null, modifier.size(TopBarMenuIconSize)) }) to onOpenBranchGraph)
                if (speechSettings?.tts != null && speechSettings?.tts != TtsProvider.NONE) {
                    add(DropdownItem(text = stringResource(if (speechSettings?.chatAutoSpeak == true) R.string.speech_disable_auto_read else R.string.speech_enable_auto_read)) to {
                        speechSettings?.let { current -> scope.launch { SpeechSettingsRepository.save(current.copy(chatAutoSpeak = !current.chatAutoSpeak)) } }
                    })
                }
                conversationTrimmed?.let { (_, count) -> add(DropdownItem(text = stringResource(R.string.conversation_trimmed_capsule, count)) to {}) }
            }
            ListPopupColumn {
                rows.forEachIndexed { index, row ->
                    DropdownImpl(item = row.first, optionSize = rows.size, isSelected = false, index = index,
                        onSelectedIndexChange = { showMenu = false; row.second() })
                }
            }
        }
    }
}

@Composable
private fun titleForRoute(route: AppRoute?): String = when (route) {
    is AppRoute.Home, is AppRoute.Browser -> ""
    is AppRoute.Terminal -> stringResource(R.string.route_terminal)
    is AppRoute.Tools -> stringResource(R.string.compact_tools_title)
    is AppRoute.WebToolsSettings -> stringResource(R.string.web_tools_settings_title)
    is AppRoute.Skills -> stringResource(R.string.route_skills)
    is AppRoute.Subagents -> "Subagent"
    is AppRoute.ConversationTitleSettings -> stringResource(R.string.conversation_title_settings_title)
    is AppRoute.AssistantOverlaySettings -> stringResource(R.string.assistant_overlay_settings_title)
    is AppRoute.Characters -> "角色"
    is AppRoute.CharacterDetail -> "角色详情"
    is AppRoute.CharacterEditor -> "编辑角色"
    is AppRoute.CharacterPersona -> "我的人设"
    is AppRoute.CharacterMemory -> "剧情记忆"
    is AppRoute.Permissions -> stringResource(R.string.route_permissions)
    is AppRoute.ConversationCategories -> stringResource(R.string.conversation_categories_title)
    is AppRoute.ConversationBranches -> stringResource(R.string.branch_graph_title)
    is AppRoute.SystemEnhance -> stringResource(R.string.route_system_enhancements)
    is AppRoute.Settings -> stringResource(R.string.route_settings)
    is AppRoute.TokenUsage -> stringResource(R.string.usage_stats_title)
    is AppRoute.DataUsage -> stringResource(R.string.data_usage_title)
    is AppRoute.SelfCheck -> "功能自检"
    is AppRoute.Support -> "支持 Eta Por"
    is AppRoute.DiagnosticsPatrol -> stringResource(R.string.diag_patrol_title)
    is AppRoute.DiagnosticsCenter -> stringResource(R.string.cleanup_diagnostics_center)
    is AppRoute.MemoryDocument -> "MEMORY.md"
    is AppRoute.PromptStudio -> stringResource(R.string.prompt_studio_title)
    is AppRoute.PromptEditor -> stringResource(R.string.prompt_studio_editor_title)
    is AppRoute.PromptVersions -> stringResource(R.string.prompt_studio_versions_title)
    is AppRoute.PromptTools -> stringResource(R.string.prompt_studio_tools_title)
    is AppRoute.PromptPresets -> stringResource(R.string.prompt_preset_title)
    is AppRoute.PromptSnapshots -> stringResource(R.string.prompt_snapshot_title)
    is AppRoute.PromptSnapshotDetail -> stringResource(R.string.prompt_snapshot_detail_title)
    is AppRoute.PromptPresetEditor -> stringResource(R.string.prompt_preset_editor_title)
    is AppRoute.PromptIntegrity -> stringResource(R.string.prompt_integrity_title)
    is AppRoute.PromptEffective -> stringResource(R.string.prompt_effective_title)
    is AppRoute.PromptEffectiveItem -> stringResource(R.string.prompt_effective_title)
    is AppRoute.PromptToolGroups -> stringResource(R.string.prompt_groups_title)
    is AppRoute.SpeechSettings -> stringResource(R.string.speech_settings_title)
    is AppRoute.SpeechRecognition -> stringResource(R.string.speech_recognition_title)
    is AppRoute.SpeechSynthesis -> stringResource(R.string.speech_synthesis_title)
    is AppRoute.SpeechOss -> stringResource(R.string.speech_oss_title)
    is AppRoute.AppearanceSettings -> stringResource(R.string.appearance_title)
    is AppRoute.DataBackup -> stringResource(R.string.data_backup_title)
    is AppRoute.Memory -> stringResource(R.string.route_memory)
    is AppRoute.Tasks -> stringResource(R.string.automation_title)
    is AppRoute.BackgroundCanvas -> stringResource(R.string.cleanup_background_canvas)
    is AppRoute.NotificationDisplaySettings -> stringResource(R.string.cleanup_notifications_fluid)
    is AppRoute.VirtualScreen -> stringResource(R.string.unified_canvas_title)
    is AppRoute.LinuxEnvironment -> stringResource(R.string.route_linux_environment)
    is AppRoute.Workspace -> stringResource(R.string.capability_workspace)
    is AppRoute.SharedFolders -> stringResource(R.string.route_shared_folders)
    is AppRoute.LinuxFiles -> stringResource(R.string.route_linux_files)
    is AppRoute.ModelProviders -> stringResource(R.string.route_model_providers)
    is AppRoute.CommunityCatalog -> "从 models.dev 添加"
    is AppRoute.CommunityCatalogProvider -> "选择模型"
    is AppRoute.McpServers -> stringResource(R.string.route_mcp_servers)
    is AppRoute.GitHubAccount -> "GitHub 账号"
    is AppRoute.McpServerDetail -> stringResource(R.string.route_mcp_server_detail)
    is AppRoute.ModelProviderDetail -> stringResource(R.string.route_provider_details)
    is AppRoute.ModelProviderNew -> stringResource(R.string.route_new_provider)
    null -> stringResource(R.string.app_name)
}

@Composable
private fun CharacterTopBarIdentity(character: RoleplayChatUi, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CharacterAvatar(
            name = character.characterName,
            avatarPath = character.avatarPath,
            size = 32.dp,
            shape = CircleShape,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = character.characterName,
            style = MiuixTheme.textStyles.body1,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 180.dp),
        )
    }
}

/** Rounded-rect press region; retain a real 48dp button and accessibility label. */
@Composable
private fun ChatTopBarMenuButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MiuixTheme.colorScheme.onSurface,
) {
    Box(
        modifier = modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).clickable(
            role = androidx.compose.ui.semantics.Role.Button,
            onClick = onClick,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp), tint = tint)
    }
}
