package io.github.mangi.eta.agent.display

import android.content.Context
import io.github.mangi.eta.agent.device.BoundedRootCommandExecutor
import io.github.mangi.eta.agent.device.DeviceToolContract
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Public-to-module integration surface. The only creator is VirtualScreenSession/RootDisplayCommandMain.
 * Agent tools use VirtualScreenUiTools with their real owner/run; UI never invents a global owner.
 */
internal object UnifiedCanvasFacade {
    private val takeoverLease = CanvasTakeoverLease()
    @Volatile private var legacyToken: CanvasTakeoverLease.Token? = null
    val userControlling: Boolean get() = takeoverLease.current != null
    data class ViewerLaunch(val submitted: Boolean, val token: CanvasTakeoverLease.Token?, val code: String)
    @Volatile var lastViewerLaunch = ViewerLaunch(false, null, "NOT_REQUESTED")
        private set

    fun beginViewer(owner: String, session: String): CanvasTakeoverLease.Token =
        takeoverLease.begin(owner, session, java.util.UUID.randomUUID().toString()).also { invalidate(it) }

    fun attachViewer(token: CanvasTakeoverLease.Token): Boolean =
        VirtualScreenSession.state.value.display?.let { it.owner == token.owner && it.sessionId == token.session } == true && takeoverLease.attach(token)

    fun viewerTokenCurrent(token: CanvasTakeoverLease.Token): Boolean = takeoverLease.current == token
    fun viewerInputCurrent(session: String, tokenId: String): Boolean = takeoverLease.permitsInput(session, tokenId)

    fun endViewer(token: CanvasTakeoverLease.Token): Boolean {
        if (!viewerTokenCurrent(token)) return false
        // Keep veto closed until evidence invalidation completes. An old Activity cannot
        // release a later token; end() compares the full identity again after invalidation.
        VirtualScreenSession.invalidateForTakeover(token.owner, token.session)
        return takeoverLease.end(token)
    }

    private fun invalidate(token: CanvasTakeoverLease.Token) {
        java.util.concurrent.CompletableFuture.runAsync {
            VirtualScreenSession.invalidateForTakeover(token.owner, token.session)
        }
    }

    fun setUserControlling(controlling: Boolean) {
        if (controlling) {
            val info = VirtualScreenSession.state.value.display ?: return
            legacyToken = beginViewer(info.owner, info.sessionId)
        } else legacyToken?.let { endViewer(it); legacyToken = null }
    }
    fun isUserViewing(sessionId: String): Boolean = takeoverLease.current?.session == sessionId
    fun clearTakeover(sessionId: String) { takeoverLease.clearSession(sessionId) }

    fun launchViewer(context: Context): ViewerLaunch {
        val info = VirtualScreenSession.state.value.display
            ?: return ViewerLaunch(false, null, "DISPLAY_UNAVAILABLE").also { lastViewerLaunch = it }
        val token = beginViewer(info.owner, info.sessionId)
        val submitted = takeoverLease.submit(token, { TakeoverActivity.submitLaunch(context, token) }, {
            VirtualScreenSession.invalidateForTakeover(token.owner, token.session)
        })
        if (!submitted) endViewer(token)
        val result = ViewerLaunch(submitted, token, if (submitted) "VIEWER_LAUNCH_SUBMITTED" else "VIEWER_LAUNCH_FAILED")
        lastViewerLaunch = result
        if (submitted) android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (viewerTokenCurrent(token)) VirtualScreenSession.invalidateForTakeover(token.owner, token.session)
            if (takeoverLease.expireUnattached(token)) {
                lastViewerLaunch = ViewerLaunch(false, token, "VIEWER_NOT_ATTACHED")
            }
        }, 10000L)
        return result
    }

    /** Live settings changes are delivered to the same helper, never a replacement display. */
    fun settingsChanged(context: Context) {
        val settings = runBlocking { SettingsDataStore.settings() }
        // Runtime's legacy wake-lock listener is an adapter of this SAME setting, not a second switch.
        io.github.mangi.eta.config.Prefs.localAgentPreferences()?.let { prefs ->
            val key = io.github.mangi.eta.config.Prefs.Keys.AGENT_KEEP_SCREEN_ON_ENABLED
            if (prefs.getBoolean(key, true) != settings.virtualScreenKeepScreenOn) {
                io.github.mangi.eta.config.Prefs.putBooleanForUi(prefs, key, settings.virtualScreenKeepScreenOn)
            }
        }
        if (!settings.virtualScreenEnabled) {
            VirtualScreenSession.revokePermission()
            MainScreenFallbackApproval.cancelAll()
            // Revoke locally BEFORE any Binder wait; failure remains observable typed state.
            runBlocking { SettingsDataStore.reconcileCanvasGate() }
            return
        }
        VirtualScreenWakeLock.sync(context, settings.virtualScreenKeepScreenOn && VirtualScreenSession.hasActiveSession())
        VirtualScreenSession.updateIdleTimeout()
        runBlocking { SettingsDataStore.reconcileCanvasGate() }
    }

    /** Explicit present_to_user/user handoff only. It is NOT an implicit failure fallback.
     * Session lock covers validation + movement; no package-only match or new-main-app fallback.
     */
    fun presentToUser(context: Context, owner: String, runId: String?,
        rootAvailable: () -> Boolean = { RootAccess.isGranted }, expectedSession: String? = null): Boolean =
        VirtualScreenSession.withOwnedRun(owner, runId, expectedSession) { info ->
            if (VirtualScreenSession.usesHookTransport(owner)) {
                val response = VirtualScreenSession.execute(context, owner, JSONObject().put("action", "handoff"), runId = runId, manual = runId == null)
                val moved = JSONObject(response.content).optBoolean("ok")
                // Keep the source viewer/session even after exact-task migration; other source tasks may exist.
                if (!moved) presentViewer(context)
                return@withOwnedRun moved
            }
            val request = JSONObject().put("action", "move_to_main").put("display_id", info.displayId)
            val command = "printf '%s\\n' ${DeviceToolContract.quote(request.toString())} | CLASSPATH=" +
                DeviceToolContract.quote(context.applicationInfo.sourceDir) +
                " app_process /system/bin io.github.mangi.eta.agent.virtualdisplay.VirtualDisplayCommandMain"
            val executor = BoundedRootCommandExecutor(AndroidAgentLogger, rootAvailable = rootAvailable)
            val moved = try {
                val result = executor.execute(command, timeoutMillis = 6000L)
                val receipt = result.stdout.lineSequence().firstOrNull { it.startsWith("ETA_VD_RESULT:") }
                    ?.removePrefix("ETA_VD_RESULT:")?.let { JSONObject(it) }
                result.exitCode == 0 && receipt?.optBoolean("ok") == true && receipt.optBoolean("verified")
            } catch (_: Exception) { false } finally { executor.close() }
            // Preserve the source session/viewer; normal lease/idle/user close owns its lifetime.
            if (!moved) presentViewer(context)
            moved
        }

    private fun presentViewer(context: Context) {
        launchViewer(context)
    }
}
