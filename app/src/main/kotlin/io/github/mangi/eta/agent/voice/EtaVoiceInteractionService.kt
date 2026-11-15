package io.github.mangi.eta.agent.voice

import android.app.KeyguardManager
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import android.os.Build
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.AssistantEntryFallback
import io.github.mangi.eta.core.assistantEntryFallback
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.core.AssistantContextFlags

class EtaVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        activeService = this
        if (Build.VERSION.SDK_INT >= 37) {
            setInvocationEffectEnabled(true)
        }
    }

    override fun onShutdown() {
        if (activeService === this) activeService = null
        super.onShutdown()
    }

    override fun onLaunchVoiceAssistFromKeyguard() {
        startActivity(
            Intent(this, EtaVoiceAssistActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun showEtaSession() {
        showSession(Bundle(), AssistantContextFlags.forSdk(Build.VERSION.SDK_INT))
    }

    companion object {
        @Volatile
        private var activeService: EtaVoiceInteractionService? = null

        internal fun requestSession(): Boolean {
            val service = activeService ?: return false
            service.showEtaSession()
            return true
        }
    }
}

class EtaVoiceAssistActivity : Activity() {
    private var entryRequested = false

    // Run only once while resumed: the existing overlay may start microphone capture,
    // so never initiate its fallback from a background/onCreate-only Activity.
    override fun onResume() {
        super.onResume()
        if (entryRequested) return
        entryRequested = true
        val requested = runCatching { EtaVoiceInteractionService.requestSession() }
            .getOrElse { throwable ->
                AndroidAgentLogger.warn("Eta assistant session_request_failed type=${throwable.safeLogType()}")
                false
            }
        if (requested) {
            AndroidAgentLogger.info("Eta assistant session_requested; presentation not yet confirmed")
            finish()
            return
        }
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false
        when (assistantEntryFallback(locked, Settings.canDrawOverlays(this))) {
            AssistantEntryFallback.LOCKED_FAILURE -> {
                AndroidAgentLogger.warn("Eta assistant visible_failure locked; no overlay fallback")
                Toast.makeText(this, "助理会话暂不可用，请解锁后重试。", Toast.LENGTH_LONG).show()
            }
            AssistantEntryFallback.OVERLAY -> {
                // No screenshot, assist structure, snapshot or screen-context acquisition.
                runCatching { EtaAssistantOverlayService.show(this, null) }
                    .onSuccess {
                        AndroidAgentLogger.info("Eta assistant overlay_fallback_requested screenContext=none; presentation not yet confirmed")
                    }
                    .onFailure { throwable ->
                        AndroidAgentLogger.warn("Eta assistant visible_failure overlay_start type=${throwable.safeLogType()}")
                        Toast.makeText(this, "无法打开助理浮窗，请稍后重试。", Toast.LENGTH_LONG).show()
                    }
            }
            AssistantEntryFallback.PERMISSION_GUIDANCE -> {
                AndroidAgentLogger.warn("Eta assistant visible_failure overlay_permission_missing")
                Toast.makeText(this, "助理会话不可用，请允许 Eta 显示悬浮窗后重试。", Toast.LENGTH_LONG).show()
                runCatching {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }.onFailure {
                    Toast.makeText(this, "请在系统设置中允许 Eta 显示悬浮窗。", Toast.LENGTH_LONG).show()
                }
            }
        }
        finish()
    }
}
