package io.github.mangi.eta.agent.display

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.SystemClock
import io.github.mangi.eta.agent.accessibility.AgentAccessibilityService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Performs an actual new screenshot, not a cached buffer timestamp refresh. */
internal object CanvasIndependentScreenshot {
    data class Capture(val bitmap: Bitmap, val capturedAtMs: Long)
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "eta-independent-capture").apply { isDaemon = true }
    }

    /** App UID: real accessibility display API and screenshot capability are required. */
    fun accessibility(displayId: Int): Capture? {
        if (Build.VERSION.SDK_INT < 30 || displayId <= 0) return null
        val service = AgentAccessibilityService.current() ?: return null
        if (service.serviceInfo.capabilities and android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT == 0) return null
        val latch = CountDownLatch(1)
        val lock = Any()
        var waiting = true
        var capture: Capture? = null
        val requestStart = SystemClock.elapsedRealtime()
        try {
            service.takeScreenshot(displayId, executor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    var copied: Bitmap? = null
                    val buffer = result.hardwareBuffer
                    try {
                        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        try { copied = hardware?.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware?.recycle() }
                        // ScreenshotResult timestamp is uptimeMillis, not JPEG completion time.
                        val age = SystemClock.uptimeMillis() - result.timestamp
                        val captured = if (age in 0..1500L) SystemClock.elapsedRealtime() - age else -1L
                        synchronized(lock) {
                            if (waiting && copied != null && captured >= requestStart) {
                                capture = Capture(copied!!, captured); copied = null
                            }
                        }
                    } finally { copied?.recycle(); buffer.close(); latch.countDown() }
                }
                override fun onFailure(errorCode: Int) { latch.countDown() }
            })
            latch.await(1300L, TimeUnit.MILLISECONDS)
        } catch (_: Exception) { /* permission/rate-limit/secure-window failure remains failure */ }
        return synchronized(lock) { waiting = false; capture }
    }

    /** Root UID only. IWindowManager maps the logical displayId to DisplayContent's layer tree;
     * this also captures while the display's output Surface is a viewer rather than ImageReader.
     * No physical-display-ID guesses, input events, surface swapping, or main-screen migration.
     */
    fun rootLogicalDisplay(displayId: Int): Capture? {
        if (android.os.Process.myUid() != 0 || Build.VERSION.SDK_INT < 34 || displayId <= 0) return null
        val lock = Any()
        val latch = CountDownLatch(1)
        var waiting = true
        var capture: Capture? = null
        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/window/ScreenCapture", "Landroid/view/")
            val listenerClass = Class.forName("android.window.ScreenCapture\$ScreenCaptureListener")
            val requestedAt = SystemClock.elapsedRealtime()
            val consumer = Consumer<Any?> { result ->
                var copied: Bitmap? = null
                var hardwareBuffer: HardwareBuffer? = null
                try {
                    if (result != null) {
                        hardwareBuffer = result.javaClass.getMethod("getHardwareBuffer").invoke(result) as? HardwareBuffer
                        val secure = result.javaClass.getMethod("containsSecureLayers").invoke(result) as Boolean
                        if (!secure) {
                            val hardware = result.javaClass.getMethod("asBitmap").invoke(result) as? Bitmap
                            try { copied = hardware?.copy(Bitmap.Config.ARGB_8888, false) } finally { hardware?.recycle() }
                        }
                        synchronized(lock) {
                            if (waiting && copied != null) {
                                // This API performs a new capture after dispatch; dispatch time is a
                                // conservative lower bound, never a timestamp assigned to cached jpeg.
                                capture = Capture(copied!!, requestedAt); copied = null
                            }
                        }
                    }
                } finally { copied?.recycle(); hardwareBuffer?.close(); latch.countDown() }
            }
            val listener = listenerClass.getConstructor(Consumer::class.java).newInstance(consumer)
            val wms = Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)
            val captureDisplay = wms.javaClass.methods.firstOrNull {
                it.name == "captureDisplay" && it.parameterCount == 3 && it.parameterTypes[0] == Int::class.javaPrimitiveType
            } ?: return null
            captureDisplay.invoke(wms, displayId, null, listener)
            latch.await(1300L, TimeUnit.MILLISECONDS)
            java.lang.ref.Reference.reachabilityFence(consumer)
        } catch (_: Exception) { /* no permission/API/no buffer => cannot issue fresh evidence */ }
        return synchronized(lock) { waiting = false; capture }
    }
}
