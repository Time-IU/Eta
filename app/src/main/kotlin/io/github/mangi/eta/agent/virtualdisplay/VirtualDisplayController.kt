package io.github.mangi.eta.agent.virtualdisplay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import android.view.Display
import androidx.compose.ui.graphics.asImageBitmap
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.display.UnifiedCanvasFacade
import io.github.mangi.eta.agent.display.VirtualScreenSession
import io.github.mangi.eta.data.datastore.SettingsDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.roundToInt

/** Compatibility UI adapter, NOT a second display engine.
 * No display creation, root hold process, global tool owner, or independent input injector exists here.
 * The capsule and the floating preview both read the same session; tools hold per-run authority there.
 */
internal object VirtualDisplayController {
    private val mutableSnapshots = MutableStateFlow(VirtualDisplaySnapshot.Inactive)
    val snapshots: StateFlow<VirtualDisplaySnapshot> = mutableSnapshots.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var expanded = false
    @Volatile private var latestSession: String? = null
    @Volatile private var appContext: Context? = null

    init {
        scope.launch {
            VirtualScreenSession.state.collect { state ->
                val info = state.display
                if (info == null) {
                    latestSession = null
                    mutableSnapshots.value = VirtualDisplaySnapshot.Inactive
                } else {
                    val previous = mutableSnapshots.value
                    val same = latestSession == info.sessionId
                    latestSession = info.sessionId
                    mutableSnapshots.value = previous.copy(active = true, displayId = info.displayId,
                        width = info.width, height = info.height, packageName = info.focusedPackage,
                        appName = info.focusedPackage, userControlling = UnifiedCanvasFacade.userControlling,
                        frame = if (same) previous.frame else null)
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            while (true) {
                delay(if (expanded) 40L else 200L)
                if (!expanded) continue
                val context = appContext ?: continue
                val info = VirtualScreenSession.state.value.display ?: continue
                // This observer cannot create a display, acquire an agent lease, or publish agent evidence.
                val result = VirtualScreenSession.observeForViewer(context, owner = info.owner)
                val encoded = result.images.firstOrNull()?.reference?.substringAfter("base64,", "") ?: continue
                val bitmap = runCatching {
                    val bytes = Base64.decode(encoded, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull() ?: continue
                if (VirtualScreenSession.state.value.display?.sessionId != info.sessionId) {
                    bitmap.recycle()
                    continue
                }
                val current = mutableSnapshots.value
                if (current.active && current.displayId == info.displayId) {
                    mutableSnapshots.value = current.copy(frame = bitmap.asImageBitmap(), frameSequence = current.frameSequence + 1)
                }
            }
        }
    }

    fun attachContext(context: Context) { appContext = context.applicationContext }
    fun isBackendAvailable(rootAvailable: Boolean, lsposedAvailable: Boolean): Boolean =
        Build.VERSION.SDK_INT >= 34 && io.github.mangi.eta.agent.display.UnifiedCanvasTransportPolicy.available(rootAvailable, lsposedAvailable)

    fun currentDisplayId(): Int = VirtualScreenSession.state.value.display?.displayId ?: Display.DEFAULT_DISPLAY
    fun setStageExpanded(expanded: Boolean) {
        this.expanded = expanded
        VirtualScreenSession.setWatchProfile("unified_capsule", if (expanded) VirtualScreenSession.WatchProfile.FLOAT else null)
    }
    fun setUserControlling(controlling: Boolean) {
        UnifiedCanvasFacade.setUserControlling(controlling)
        mutableSnapshots.value = mutableSnapshots.value.copy(userControlling = UnifiedCanvasFacade.userControlling)
    }
    private val screenshotCapture = BoundedCanvasCapture<Bitmap>(
        java.util.concurrent.Executors.newSingleThreadExecutor { task ->
            Thread(task, "eta-canvas-screenshot").apply { isDaemon = true }
        }, { it.recycle() })

    /** Independent, request-time frame from the current session, including collapsed previews.
     * Synchronous compatibility API: main-thread calls fail promptly instead of blocking UI.
     */
    fun captureScreenshotBitmap(waitUpToMs: Long = 450L): Bitmap? = screenshotCapture.capture(
        waitUpToMs, android.os.Looper.myLooper() == android.os.Looper.getMainLooper(),
    ) {
        val context = appContext ?: return@capture null
        val info = VirtualScreenSession.state.value.display ?: return@capture null
        val started = android.os.SystemClock.elapsedRealtime()
        val result = VirtualScreenSession.observeForViewer(context, owner = info.owner)
        val response = JSONObject(result.content)
        fun confirmed(): Boolean {
            val current = VirtualScreenSession.state.value.display
            return CanvasScreenshotPolicy.confirmed(info.sessionId, current?.sessionId,
                info.manualInputGeneration, current?.manualInputGeneration, info.displayId,
                response.optInt("displayId", -1), response.optBoolean("ok"),
                response.optLong("frameId"), response.optLong("capturedAtMs", -1),
                response.optLong("manualGeneration", -1), started, android.os.SystemClock.elapsedRealtime())
        }
        if (!confirmed()) return@capture null
        val encoded = result.images.firstOrNull()?.reference?.substringAfter("base64,", "")
            ?: return@capture null
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@capture null
        try {
            if (!confirmed()) null else CanvasBitmapOwnership.copyForCaller(decoded)
        } finally { decoded.recycle() }
    }
    fun onTopPackageObserved(context: Context, packageName: String, rootAvailable: () -> Boolean = { RootAccess.isGranted }) {
        attachContext(context)
        val info = VirtualScreenSession.state.value.display ?: return
        mutableSnapshots.value = mutableSnapshots.value.copy(packageName = packageName, appName = packageName,
            displayId = info.displayId)
    }
    fun emitTapEffect(x: Int, y: Int, longPress: Boolean = false) {
        val info = VirtualScreenSession.state.value.display ?: return
        VirtualScreenSession.showNodeGesture(info, if (longPress) "long_press" else "tap", x, y)
    }
    fun emitSwipeEffect(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 420) {
        val info = VirtualScreenSession.state.value.display ?: return
        VirtualScreenSession.showNodeGesture(info, "swipe", x1, y1, durationMs, x2, y2)
    }
    fun dispatchUserTapOnCanvas(normalizedX: Float, normalizedY: Float, longPress: Boolean = false,
        rootAvailable: () -> Boolean = { RootAccess.isGranted }) {
        dispatchManual(JSONObject().put("action", if (longPress) "long_press" else "tap"),
            normalizedX, normalizedY)
    }
    fun dispatchUserSwipeOnCanvas(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Int = 320,
        rootAvailable: () -> Boolean = { RootAccess.isGranted }) {
        dispatchManual(JSONObject().put("action", "swipe").put("durationMs", durationMs.coerceIn(140, 1000)),
            startX, startY, endX, endY)
    }
    private fun dispatchManual(args: JSONObject, x: Float, y: Float, endX: Float? = null, endY: Float? = null) {
        val context = appContext ?: return
        val info = VirtualScreenSession.state.value.display ?: return
        if (!UnifiedCanvasFacade.userControlling) return
        args.put("x", (x.coerceIn(0f, 1f) * (info.width - 1)).roundToInt())
            .put("y", (y.coerceIn(0f, 1f) * (info.height - 1)).roundToInt())
        if (endX != null && endY != null) args
            .put("x1", args.getInt("x")).put("y1", args.getInt("y"))
            .put("x2", (endX.coerceIn(0f, 1f) * (info.width - 1)).roundToInt())
            .put("y2", (endY.coerceIn(0f, 1f) * (info.height - 1)).roundToInt())
        scope.launch(Dispatchers.IO) {
            if (UnifiedCanvasFacade.userControlling && VirtualScreenSession.state.value.display?.sessionId == info.sessionId) {
                VirtualScreenSession.inputForViewer(context, info.sessionId, args)
            }
        }
    }
    fun handoffToMainDisplay(context: Context, rootAvailable: () -> Boolean = { RootAccess.isGranted }): Boolean {
        val info = VirtualScreenSession.state.value.display ?: return false
        return UnifiedCanvasFacade.presentToUser(context, info.owner, null, rootAvailable, info.sessionId)
    }
    /** Legacy Runtime has no ownership over this session. Run leases/settings/service own release.
     * A late runtime onDestroy/cancel must never destroy a new run's display.
     */
    fun release(context: Context? = null, rootAvailable: () -> Boolean = { RootAccess.isGranted }) {
        setStageExpanded(false)
    }
}
