package io.github.mangi.eta.agent.display

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.InputEvent
import android.view.KeyEvent
import io.github.mangi.eta.EtaApp
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import io.github.mangi.eta.agent.virtualdisplay.CanvasRoutingPolicy
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Compatibility transport of the SAME VirtualScreenSession. Process-shaped bounded protocol lets
 * existing lease, deadline, cancellation, viewer and observation code stay the single authority.
 * No singleton/session table/routing lives here. It can only be instantiated inside the session
 * creation gate. XposedService is a framework connection, NOT a shell or privileged Context API.
 * The existing system_server hook authorizes the narrowly named display creation; normal App UID
 * and accessibility permissions still apply to each command. No su, UID spoofing or global input.
 */
internal class HookCanvasProcess(private val context: Context) : CanvasProtocolProcess() {
    private val resourcesLock = Any()
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var worker: HandlerThread? = null
    private var displayListener: DisplayManager.DisplayListener? = null
    private var jpeg: ByteArray? = null
    private var continuedStroke: android.accessibilityservice.GestureDescription.StrokeDescription? = null
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var frameId = 0L
    private var capturedAtMs = 0L
    private var capturedGeneration = -1L
    @Volatile private var manualGeneration = 0L
    @Volatile private var frameIntervalMs = 200L
    @Volatile private var jpegQuality = 80
    @Volatile private var lastDemandAt = 0L
    @Volatile private var lastFrameAt = 0L
    private var width = 0
    private var height = 0
    private var allowOff = false
    @Volatile private var lastManualInputAt = 0L
    @Volatile var inputAuthorization: ((String, Long) -> String?)? = null
    private var idle: VirtualScreenIdleTimer? = null
    @Volatile private var setup: JSONObject? = null
    private val manager = context.getSystemService(DisplayManager::class.java)
    private fun id(): Int = synchronized(resourcesLock) {
        val id = display?.display?.displayId ?: error("DISPLAY_GONE")
        require(id > 0 && manager?.getDisplay(id)?.isValid == true) { "DISPLAY_GONE" }
        id
    }
    private fun success() = JSONObject().put("ok", true).put("displayId", id())
        .put("transport", "hook_in_process")
    private fun failure(code: String) = JSONObject().put("ok", false).put("code", code)
        .put("transport", "hook_in_process").put("requires_observation", true)

    private fun create(args: JSONObject): JSONObject {
        require(setup == null && alive.get()) { "DISPLAY_ALREADY_ACTIVE" }
        require(EtaApp.serviceInstance != null) { "HOOK_UNAVAILABLE" }
        require(android.os.Build.VERSION.SDK_INT >= 34) { "DEVICE_UNSUPPORTED" }
        width = args.getInt("width"); height = args.getInt("height")
        require(width in 320..1440 && height in 480..3200) { "INVALID_ARGUMENT" }
        val density = args.getInt("density"); require(density in 120..640) { "INVALID_ARGUMENT" }
        allowOff = args.optBoolean("allowScreenOff")
        HiddenApiBypass.addHiddenApiExemptions("Landroid/hardware/display/")
        val thread = HandlerThread("eta-hook-canvas-frames").apply { start() }
        synchronized(resourcesLock) {
            check(alive.get()) { "DISPLAY_CANCELLED" }
            worker = thread
            val frames = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = frames
            frames.setOnImageAvailableListener({ source ->
                runCatching { source.acquireLatestImage()?.use { image ->
                    val now = SystemClock.elapsedRealtime()
                    val generation = manualGeneration
                    val captured = CanvasReviewSafety.captureElapsed(image.timestamp, System.nanoTime(), now)
                    if (captured <= 0L || captured > now || now - captured > 1500L) return@use
                    if (now - lastDemandAt > 1500L || now - lastFrameAt < frameIntervalMs) return@use
                    lastFrameAt = now
                    val plane = image.planes.first()
                    require(plane.pixelStride == 4) { "UNSUPPORTED_FRAME_FORMAT" }
                    val source = plane.buffer.duplicate()
                    val packed = java.nio.ByteBuffer.allocate(image.width * image.height * 4)
                    for (row in 0 until image.height) {
                        val start = row * plane.rowStride
                        source.limit(plane.buffer.limit())
                        source.position(start); source.limit(start + image.width * 4)
                        packed.put(source)
                    }
                    packed.flip()
                    val bitmap = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(packed)
                    val bytes = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, bytes)
                    bitmap.recycle()
                    synchronized(resourcesLock) {
                        if (generation == manualGeneration) {
                            jpeg = bytes.toByteArray(); frameId++
                            capturedAtMs = captured; capturedGeneration = generation
                        }
                    }
                } }
            }, Handler(thread.looper))
            // Exact original in-process Hook path; no new permission exemption or broader name.
            val config = VirtualDisplayConfig.Builder(CanvasRoutingPolicy.VIRTUAL_DISPLAY_NAME, width, height, density)
                .setSurface(frames.surface).setFlags(CanvasRoutingPolicy.TRUSTED_INTERACTIVE_FLAGS).build()
            display = manager?.createVirtualDisplay(config) ?: error("HOOK_DISPLAY_CREATE_FAILED")
            val created = display!!.display
            require(created.displayId > 0 && CanvasRoutingPolicy.hasRequiredDisplayFlags(created.flags)) { "DISPLAY_ISOLATION_UNAVAILABLE" }
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == created.displayId && manager.getDisplay(displayId)?.isValid != true) destroy()
                }
                override fun onDisplayRemoved(displayId: Int) { if (displayId == created.displayId) destroy() }
            }
            displayListener = listener
            manager.registerDisplayListener(listener, Handler(thread.looper))
            setup = JSONObject(args.toString())
            idle = VirtualScreenIdleTimer(args.optInt("idleTimeoutMinutes", 20), SystemClock::elapsedRealtime, args.optBoolean("activeRun"))
        }
        Thread({
            while (alive.get()) {
                Thread.sleep(250L)
                if (EtaApp.serviceInstance == null || idle?.expired() == true) { destroy(); break }
            }
        }, "eta-hook-canvas-lifecycle").apply { isDaemon = true; start() }
        return success().put("width", width).put("height", height).put("density", density)
            .put("captureWidth", width).put("captureHeight", height).put("direct", false)
    }

    override fun execute(args: JSONObject): JSONObject {
        val action = args.getString("action")
        manualGeneration = args.optLong("manualGeneration", manualGeneration)
        if (action == "create") return create(args)
        if (action == "close") return JSONObject().put("ok", true)
        require(EtaApp.serviceInstance != null) { "HOOK_UNAVAILABLE" }
        val displayId = id()
        if (action == "lifecycle") {
            idle?.update(args.optInt("idleTimeoutMinutes", 20), args.optBoolean("activeRun"))
            frameIntervalMs = args.optLong("frameIntervalMs", frameIntervalMs).coerceIn(40L, 1000L)
            jpegQuality = args.optInt("jpegQuality", jpegQuality).coerceIn(40, 100)
            return success()
        }
        require(allowOff || context.getSystemService(PowerManager::class.java)?.isInteractive == true && !VirtualScreenSession.mainScreenLocked(context)) { "SCREEN_OFF_PERMISSION_REQUIRED" }
        if (action in setOf("tap", "long_press", "swipe", "touch", "text", "key", "back")) {
            if (!args.optBoolean("userInput")) {
                val denial = inputAuthorization?.invoke(args.optString("authorizedRunId"), args.optLong("manualGeneration", -1L)) ?: "DISPLAY_RUN_MISMATCH"
                require(denial.isEmpty()) { denial }
            } else lastManualInputAt = SystemClock.elapsedRealtime()
        }
        val service = AgentAccessibilityService.current()
        fun accessibility() = requireNotNull(service) { "ACCESSIBILITY_UNAVAILABLE" }
        fun input(result: AgentAccessibilityService.NodeActionResult): JSONObject =
            if (result.ok) success() else failure(result.code)
        if (action !in setOf("observe", "probe", "tasks", "host_surface", "ime")) idle?.touch()
        return when (action) {
            "host_surface" -> success().put("direct", false)
            "probe" -> success().put("width", width).put("height", height)
                .put("packageName", service?.currentPackageName(displayId).orEmpty())
            "observe" -> observeFrame(args)
            "launch" -> {
                val component = ComponentName.unflattenFromString(args.getString("component")) ?: error("INVALID_COMPONENT")
                // Without either a real main-display observation or visible task evidence, do not
                // guess that starting a single-task app cannot pull the user's foreground task.
                val main = accessibility().currentPackageName(Display.DEFAULT_DISPLAY)
                if (main == component.packageName && args.optBoolean("restartApp")) return failure("HOOK_APP_RESTART_REQUIRES_ROOT")
                if (main == component.packageName) return failure("APP_ALREADY_RUNNING").put("component", component.flattenToString()).put("uri", args.optString("uri"))
                // Never force-stop/restart from an App UID merely because a caller supplied a boolean.
                val intent = if (args.optString("uri").isBlank()) Intent.makeMainActivity(component)
                    else Intent(Intent.ACTION_VIEW, android.net.Uri.parse(args.getString("uri"))).setComponent(component)
                startActivityBound(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), displayId)
                success().put("launch_status", "unconfirmed").put("requires_observation", true)
            }
            "tap", "long_press" -> input(accessibility().gestureTap(pixel(args, "x", width), pixel(args, "y", height),
                if (action == "long_press") args.optLong("durationMs", 650L) else 50L, displayId))
            "swipe" -> input(accessibility().gestureSwipe(pixel(args, "x1", width), pixel(args, "y1", height),
                pixel(args, "x2", width), pixel(args, "y2", height), args.optLong("durationMs", 320L), displayId))
            "text" -> {
                require(args.getString("text").length <= 20000) { "TEXT_TOO_LONG" }
                input(if (args.optBoolean("replace")) accessibility().setTextNode(null, null, args.getString("text"), displayId)
                else accessibility().inputTextFocused(args.getString("text"), displayId))
            }
            "key", "back" -> {
                val button = if (action == "back") "BACK" else args.getString("button")
                if (button == "ENTER") return input(accessibility().imeEnter(displayId))
                if (button in setOf("HOME", "APPS", "MENU")) {
                    val home = Intent(context, VirtualScreenHomeActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("mode", button)
                    startActivityBound(home, displayId)
                    return success()
                }
                val code = when (button) {
                    "BACK" -> KeyEvent.KEYCODE_BACK
                    "ENTER" -> KeyEvent.KEYCODE_ENTER
                    "DELETE" -> KeyEvent.KEYCODE_DEL
                    else -> return failure("HOOK_KEY_UNSUPPORTED")
                }
                if (injectKey(displayId, code)) success() else failure("HOOK_INPUT_PERMISSION_DENIED")
            }
            "ime" -> {
                val snapshot = service?.captureNodeSnapshot(120, displayId)?.takeIf { it.displayId == displayId }
                    ?: return failure("IME_STATE_UNKNOWN").put("state_known", false)
                val manual = args.optBoolean("userInput") && SystemClock.elapsedRealtime() - lastManualInputAt <= 4000L
                val state = CanvasReviewSafety.Ime(true, snapshot.nodes.any { it.editable && it.focused && it.enabled }, manual)
                val shown = runCatching { service.windows.any { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrNull()
                success().put("state_known", true).put("editableFocused", state.editableFocused)
                    .put("manualInput", manual).put("bridgeRequested", state.bridgeRequested)
                    .put("inputShown", shown?.toString() ?: JSONObject.NULL)
                    .put("displayRequested", state.bridgeRequested.toString())
                    .put("displaySession", state.editableFocused.toString())
            }
            "tasks" -> success().put("tasks", JSONArray(tasks().filter { taskDisplayId(it) == displayId }.map { task ->
                JSONObject().put("taskId", task.taskId).put("packageName", task.topActivity?.packageName.orEmpty())
            }))
            "switch_task" -> {
                val taskId = args.getInt("taskId")
                require(tasks().any { it.taskId == taskId && taskDisplayId(it) == displayId }) { "HOOK_TASK_QUERY_UNAVAILABLE" }
                val activity = context.getSystemService(android.app.ActivityManager::class.java)
                activity.moveTaskToFront(taskId, 0, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
                success()
            }
            "handoff" -> if (moveToMain(displayId)) success() else failure("HOOK_HANDOFF_UNCONFIRMED")
            "touch" -> touch(accessibility(), args, displayId)
            else -> failure("VIRTUAL_ACTION_UNSUPPORTED")
        }
    }


    private fun observeFrame(args: JSONObject): JSONObject {
        val minimum = args.optLong("notBeforeMs", SystemClock.elapsedRealtime())
        val after = args.optLong("afterFrameId", 0L)
        val expected = args.optLong("manualGeneration", manualGeneration)
        lastDemandAt = SystemClock.elapsedRealtime()
        val independent = CanvasNewCapturePolicy.request(minimum, expected, SystemClock::elapsedRealtime,
            { manualGeneration }, {
                CanvasIndependentScreenshot.accessibility(id())?.let { captured ->
                    try {
                        val bytes = ByteArrayOutputStream()
                        captured.bitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, bytes)
                        CanvasNewCapturePolicy.Frame(bytes.toByteArray(), captured.capturedAtMs, expected)
                    } finally { captured.bitmap.recycle() }
                }
            })
        if (independent != null) synchronized(resourcesLock) {
            if (alive.get() && manualGeneration == expected) {
                jpeg = independent.image; capturedAtMs = independent.capturedAtMs
                capturedGeneration = expected; frameId++
            }
        }
        val until = SystemClock.elapsedRealtime() + 1200L
        while (alive.get() && SystemClock.elapsedRealtime() < until) {
            val frame = synchronized(resourcesLock) {
                if (CanvasReviewSafety.freshFrame(frameId, capturedAtMs, capturedGeneration, after,
                        minimum, expected, SystemClock.elapsedRealtime())) {
                    jpeg?.let { bytes ->
                        if (bytes.size > 1900000) return failure("DISPLAY_FRAME_TOO_LARGE")
                        success().put("image", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("frameId", frameId)
                            .put("capturedAtMs", capturedAtMs).put("manualGeneration", capturedGeneration)
                            .put("frameAgeMs", SystemClock.elapsedRealtime() - capturedAtMs)
                            .put("width", width).put("height", height).put("mimeType", "image/jpeg")
                    }
                } else null
            }
            if (frame != null) return frame
            Thread.sleep(15L)
        }
        return failure("DISPLAY_FRAME_PENDING")
    }

    /** Keep the source display alive through launch dispatch. A cancelled/destroyed display must
     * never become an ActivityOptions request to an absent display that Android might reinterpret.
     */
    private fun startActivityBound(intent: Intent, target: Int) = synchronized(resourcesLock) {
        require(alive.get()) { "DISPLAY_CANCELLED" }
        id()
        context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle())
    }

    private fun pixel(args: JSONObject, key: String, bound: Int): Float {
        val value = args.getInt(key)
        require(value in 0 until bound) { "INVALID_COORDINATE" }
        return value.toFloat()
    }

    private fun tasks(): List<android.app.TaskInfo> = runCatching {
        val cls = Class.forName("android.app.ActivityTaskManager")
        val service = cls.getMethod("getService").invoke(null)
        val method = service.javaClass.methods.first { it.name == "getTasks" && it.parameterCount in setOf(1, 3, 4) }
        val result = when (method.parameterCount) {
            1 -> method.invoke(service, 40)
            3 -> method.invoke(service, 40, false, false)
            else -> method.invoke(service, 40, false, false, Display.INVALID_DISPLAY)
        }
        (result as? List<*>)?.filterIsInstance<android.app.TaskInfo>().orEmpty()
    }.getOrDefault(emptyList())

    private fun taskDisplayId(task: android.app.TaskInfo): Int = runCatching {
        android.app.TaskInfo::class.java.getField("displayId").getInt(task)
    }.getOrDefault(Display.INVALID_DISPLAY)

    private fun moveToMain(displayId: Int): Boolean = CanvasTaskHandoff.move(displayId).let {
        it.optBoolean("ok") && it.optBoolean("verified")
    }

    /** Display-bound accessibility continuation; no global touch or main-screen emulation. */
    private fun touch(service: AgentAccessibilityService, args: JSONObject, displayId: Int): JSONObject {
        val phase = args.getString("phase")
        val x = args.optDouble("x", lastTouchX.toDouble()).toFloat().coerceIn(0f, (width - 1).toFloat())
        val y = args.optDouble("y", lastTouchY.toDouble()).toFloat().coerceIn(0f, (height - 1).toFloat())
        require(phase in setOf("down", "move", "up", "cancel")) { "INVALID_ARGUMENT" }
        val path = android.graphics.Path()
        val previous = continuedStroke
        if (phase == "down") path.moveTo(x, y) else {
            require(previous != null) { "HOOK_TOUCH_SEQUENCE_INVALID" }
            path.moveTo(lastTouchX, lastTouchY); path.lineTo(x, y)
        }
        val continues = phase == "down" || phase == "move"
        val stroke = if (phase == "down") android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 16L, true)
            else previous!!.continueStroke(path, 0L, 16L, continues)
        val gesture = android.accessibilityservice.GestureDescription.Builder().setDisplayId(displayId).addStroke(stroke).build()
        val done = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        Handler(android.os.Looper.getMainLooper()).post {
            if (!alive.get()) { done.countDown(); return@post }
            if (!args.optBoolean("userInput")) {
                val denial = inputAuthorization?.invoke(args.optString("authorizedRunId"), args.optLong("manualGeneration", -1L))
                    ?: "DISPLAY_RUN_MISMATCH"
                if (denial.isNotEmpty()) { done.countDown(); return@post }
            }
            val started = service.dispatchGesture(gesture, object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: android.accessibilityservice.GestureDescription) { accepted.set(true); done.countDown() }
                override fun onCancelled(gestureDescription: android.accessibilityservice.GestureDescription) { done.countDown() }
            }, null)
            if (!started) done.countDown()
        }
        if (!done.await(1500L, TimeUnit.MILLISECONDS)) { continuedStroke = null; return failure("ACTION_OUTCOME_UNKNOWN") }
        if (!accepted.get()) { continuedStroke = null; return failure("HOOK_TOUCH_REJECTED") }
        continuedStroke = stroke.takeIf { continues }; lastTouchX = x; lastTouchY = y
        return success().put("phase", phase).put("injected", true)
    }

    private fun injectKey(displayId: Int, key: Int): Boolean = runCatching {
        val type = Class.forName("android.hardware.input.InputManager")
        val input = type.getMethod("getInstance").invoke(null)
        val inject = type.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        val target = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
        val now = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(now, now, action, key, 0)
            target.invoke(event, displayId)
            if (inject.invoke(input, event, 2) != true) return false
        }
        true
    }.getOrDefault(false)

    override fun releaseResources() {
        synchronized(resourcesLock) {
            displayListener?.let { runCatching { manager?.unregisterDisplayListener(it) } }
            runCatching { display?.release() }; display = null
            runCatching { reader?.close() }; reader = null
            worker?.quitSafely(); worker = null; jpeg = null
        }
    }
}
