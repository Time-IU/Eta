package io.github.mangi.eta.ui

import android.app.UiModeManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import io.github.mangi.eta.agent.runtime.AgentRuntimeService
import io.github.mangi.eta.agent.voice.EtaAssistantOverlayService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.model.AppearanceThemeMode
import io.github.mangi.eta.data.repository.AppearanceSettingsRepository
import io.github.mangi.eta.ui.app.AgentAppRoot
import io.github.mangi.eta.ui.app.AgentAppTheme
import io.github.mangi.eta.ui.app.PredictiveBackController
import io.github.mangi.eta.ui.app.installStartupSplash
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.automation.AutoBackupAlarm
import io.github.mangi.eta.data.repository.AutoBackupRunner
import io.github.mangi.eta.hook.vivo.VivoHandoff

class MainActivity : ComponentActivity() {
    private var launchRequests by mutableStateOf(MainActivityLaunchRequests())
    private var appliedPredictiveBackEnabled = true
    private var assistantConversationSource by mutableStateOf(AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE)
    private var linuxEnvironmentRequested by mutableStateOf(false)
    private var lastAutoBackupCheckAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidAgentLogger.info("Agent UI created: task=$taskId restored=${savedInstanceState != null}")
        var contentReady = false
        installStartupSplash { contentReady }
        enableEdgeToEdge()
        launchRequests = MainActivityLaunchRequests.restore(savedInstanceState, intent)
        if (savedInstanceState != null) {
            assistantConversationSource = savedInstanceState.getString("eta_execution_source")
                ?: AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
            linuxEnvironmentRequested = savedInstanceState.getBoolean("eta_open_linux_environment")
        } else {
            acceptAdditionalLaunchRequest(intent)
        }
        lifecycleScope.launch {
            val initialAppearance = AppearanceSettingsRepository.settings()
            appliedPredictiveBackEnabled = initialAppearance.predictiveBackEnabled
            setContent {
                val appearance by AppearanceSettingsRepository.settingsFlow()
                    .collectAsState(initial = initialAppearance)

                LaunchedEffect(appearance.themeMode) {
                    updateApplicationNightMode(appearance.themeMode)
                }

                LaunchedEffect(appearance.predictiveBackEnabled) {
                    val enabled = appearance.predictiveBackEnabled
                    if (enabled != appliedPredictiveBackEnabled &&
                        PredictiveBackController.apply(applicationInfo, enabled)
                    ) {
                        appliedPredictiveBackEnabled = enabled
                        recreateWithoutTransition()
                    }
                }

                AgentAppTheme(
                    appearance = appearance,
                    applyInterfaceScale = true,
                    onResolvedDarkModeChange = ::updateSystemBars,
                ) {
                    AgentAppRoot(
                        assistantConversationKey = launchRequests.assistantConversationKey,
                        assistantConversationSource = assistantConversationSource,
                        openLinuxEnvironment = linuxEnvironmentRequested,
                        onLinuxEnvironmentOpened = {
                            linuxEnvironmentRequested = false
                            intent?.action = null
                        },
                        requestedConversationId = launchRequests.conversationId,
                        onRequestedConversationOpened = {
                            launchRequests = launchRequests.copy(conversationId = null)
                            intent?.action = null
                        },
                        requestedArchivedConversation = launchRequests.archivedConversation,
                        onArchivedConversationOpened = {
                            launchRequests = launchRequests.copy(archivedConversation = null)
                            intent?.action = null
                        },
                        sharedContent = launchRequests.sharedContent,
                        onSharedContentAccepted = {
                            launchRequests = launchRequests.copy(sharedContent = null)
                            intent?.action = null
                        },
                        openSpeechSettings = launchRequests.speechSettings,
                        openTerminal = launchRequests.terminal,
                        onTerminalOpened = {
                            launchRequests = launchRequests.copy(terminal = false)
                            intent?.action = null
                        },
                        onSpeechSettingsOpened = {
                            launchRequests = launchRequests.copy(speechSettings = false)
                            intent?.action = null
                        },
                        onAssistantConversationOpened = { opened ->
                            launchRequests = launchRequests.copy(assistantConversationKey = null)
                            intent?.action = null
                            if (opened && assistantConversationSource == AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE) {
                                EtaAssistantOverlayService.notifyHandoffReady(this@MainActivity)
                            }
                        },
                    )
                }
            }
            contentReady = true
        }
    }

    override fun onStart() {
        super.onStart()
        startedInstances.add(this)
        isInForeground = true
        AgentRuntimeService.onEtaUiVisibilityChanged()
    }

    override fun onStop() {
        startedInstances.remove(this)
        isInForeground = startedInstances.isNotEmpty() || isChangingConfigurations
        AgentRuntimeService.onEtaUiVisibilityChanged()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val fromLauncher = intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
        AndroidAgentLogger.info("Agent UI new intent: task=$taskId launcher=$fromLauncher")
        setIntent(intent)
        launchRequests = launchRequests.accept(intent)
        acceptAdditionalLaunchRequest(intent)
    }

    override fun onDestroy() {
        startedInstances.remove(this)
        isInForeground = startedInstances.isNotEmpty() || isChangingConfigurations
        AndroidAgentLogger.info(
            "Agent UI destroyed: task=$taskId changing_configuration=$isChangingConfigurations finishing=$isFinishing",
        )
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        launchRequests.saveTo(outState)
        outState.putString("eta_execution_source", assistantConversationSource)
        outState.putBoolean("eta_open_linux_environment", linuxEnvironmentRequested)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        val now = System.currentTimeMillis()
        if (now - lastAutoBackupCheckAt < AUTO_BACKUP_CHECK_INTERVAL_MS) return
        lastAutoBackupCheckAt = now
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { AutoBackupAlarm.refresh(applicationContext) }
            runCatching { AutoBackupRunner.maybeRun(applicationContext) }
        }
    }

    private fun acceptAdditionalLaunchRequest(request: Intent?) {
        when (request?.action) {
            ACTION_OPEN_LINUX_ENVIRONMENT -> linuxEnvironmentRequested = true
            ACTION_VIEW_EXECUTION -> {
                assistantConversationSource = request.getStringExtra(EXTRA_EXECUTION_SOURCE)
                    ?.takeIf { it in setOf(
                        VivoHandoff.SOURCE, AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE,
                        AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE, "automation",
                    ) } ?: AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
                launchRequests = launchRequests.copy(
                    assistantConversationKey = request.getStringExtra(
                        EtaAssistantOverlayService.EXTRA_CONVERSATION_KEY,
                    )?.takeIf(String::isNotBlank),
                )
                request.action = null
            }
            EtaAssistantOverlayService.ACTION_OPEN_CONVERSATION -> {
                assistantConversationSource = AgentRuntimeWire.ETA_VOICE_HANDOFF_SOURCE
            }
            ACTION_OPEN_CONVERSATION_ID -> {
                request.getStringExtra(EXTRA_CONVERSATION_ID)?.takeIf(String::isNotBlank)?.let { id ->
                    runCatching {
                        startService(Intent(this, AgentRuntimeService::class.java)
                            .setAction(AgentRuntimeService.ACTION_MARK_READ)
                            .putExtra(AgentRuntimeService.EXTRA_MARK_READ_CONVERSATION_ID, id))
                    }
                }
            }
        }
    }

    private fun updateApplicationNightMode(themeMode: AppearanceThemeMode) {
        val mode = when (themeMode) {
            AppearanceThemeMode.LIGHT -> UiModeManager.MODE_NIGHT_NO
            AppearanceThemeMode.DARK -> UiModeManager.MODE_NIGHT_YES
            // 应用级 AUTO 清除夜间模式覆盖，恢复跟随系统。
            AppearanceThemeMode.SYSTEM -> UiModeManager.MODE_NIGHT_AUTO
        }
        getSystemService(UiModeManager::class.java).setApplicationNightMode(mode)
    }

    private fun updateSystemBars(isDark: Boolean) {
        val style = if (isDark) {
            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        } else {
            SystemBarStyle.light(
                scrim = android.graphics.Color.TRANSPARENT,
                darkScrim = android.graphics.Color.TRANSPARENT,
            )
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
        window.decorView.post {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun recreateWithoutTransition() {
        overridePendingTransition(0, 0)
        recreate()
        overridePendingTransition(0, 0)
    }

    companion object {
        private val startedInstances = Collections.newSetFromMap(IdentityHashMap<MainActivity, Boolean>())

        @Volatile
        internal var isInForeground: Boolean = false
            private set

        private const val AUTO_BACKUP_CHECK_INTERVAL_MS = 5 * 60 * 1000L
        const val ACTION_OPEN_LINUX_ENVIRONMENT = "io.github.mangi.eta.action.OPEN_LINUX_ENVIRONMENT"
        const val ACTION_VIEW_EXECUTION = "io.github.mangi.eta.action.VIEW_EXECUTION"
        const val EXTRA_EXECUTION_SOURCE = "io.github.mangi.eta.extra.EXECUTION_SOURCE"
        const val ACTION_OPEN_TERMINAL = "io.github.mangi.eta.action.OPEN_TERMINAL"
        /** 运行浮层的结果卡片回到本体：App 发起的 run 带会话 ID，直接打开该会话。 */
        const val ACTION_OPEN_CONVERSATION_ID = "io.github.mangi.eta.action.OPEN_CONVERSATION_ID"
        const val EXTRA_CONVERSATION_ID = "io.github.mangi.eta.extra.CONVERSATION_ID"
        /** 系统助手等外部入口发起的 run 按来源与会话键归档，回到本体时打开对应的归档会话。 */
        const val ACTION_OPEN_ARCHIVED_CONVERSATION = "io.github.mangi.eta.action.OPEN_ARCHIVED_CONVERSATION"
        const val EXTRA_HANDOFF_SOURCE = "io.github.mangi.eta.extra.HANDOFF_SOURCE"
        const val EXTRA_CONVERSATION_KEY = "io.github.mangi.eta.extra.CONVERSATION_KEY"
    }
}
