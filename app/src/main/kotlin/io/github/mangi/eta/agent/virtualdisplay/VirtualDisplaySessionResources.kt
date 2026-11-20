package io.github.mangi.eta.agent.virtualdisplay

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.HandlerThread
import io.github.mangi.eta.agent.device.DeviceToolContract
import io.github.mangi.eta.agent.terminal.ShellProcessSupervisor
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.json.JSONObject

/** 已分离的资源只在工作线程回收，不再持有 Controller 的状态锁。 */
internal class VirtualDisplaySessionResources(
    val reader: ImageReader?,
    val frameWorker: HandlerThread?,
    val virtualDisplay: VirtualDisplay?,
    val bridge: RootVirtualDisplayBridge?,
    val manager: DisplayManager?,
    val listener: DisplayManager.DisplayListener?,
) {
    fun close() {
        fun closePart(block: () -> Unit) {
            try {
                block()
            } catch (exception: Exception) {
                AndroidAgentLogger.warnThrottled("virtual_display_cleanup_failed") {
                    "Virtual display cleanup failed: type=${exception.safeLogType()}"
                }
            }
        }
        closePart { listener?.let { manager?.unregisterDisplayListener(it) } }
        closePart { reader?.setOnImageAvailableListener(null, null) }
        closePart { reader?.close() }
        closePart { frameWorker?.quitSafely() }
        closePart { virtualDisplay?.release() }
        closePart { bridge?.close() }
    }
}

internal class RootVirtualDisplayBridge private constructor(
    val displayId: Int,
    val process: Process,
    private val supervisor: ShellProcessSupervisor,
    private val disconnected: AtomicBoolean,
) {
    val isAlive: Boolean
        get() = !disconnected.get() && process.isAlive

    fun close() {
        try {
            process.outputStream.write("release\n".toByteArray(Charsets.UTF_8))
            process.outputStream.flush()
            process.outputStream.close()
        } catch (_: Exception) {
            // 断开的管道交给下面的进程树回收处理。
        }
        supervisor.beginClosing()
        try {
            if (process.isAlive) supervisor.terminateAndReap(process) else supervisor.reapProcess(process)
        } finally {
            supervisor.unregisterProcess(process)
        }
    }

    companion object {
        fun create(
            appContext: Context,
            surface: android.view.Surface,
            width: Int,
            height: Int,
            densityDpi: Int,
            rootAvailable: () -> Boolean,
            onDisconnected: (Process) -> Unit,
        ): RootVirtualDisplayBridge? {
            // Binary/source compatibility only. Display creation belongs to the unified session.
            return null
        }

        internal fun formatFailureSummary(
            resultLine: String?,
            awaited: Boolean,
            resolvedDisplayId: Int?,
            disconnected: Boolean,
            alive: Boolean,
        ): String {
            val rawJson = resultLine?.removePrefix(VirtualDisplayCommandMain.RESULT_PREFIX)?.trim()
            val parsed = rawJson?.takeIf { it.startsWith("{") }?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (parsed != null) {
                val parts = buildList {
                    parsed.optString("code").takeIf { it.isNotBlank() }?.let { add("code=$it") }
                    parsed.optString("reason").takeIf { it.isNotBlank() }?.let { add("reason=$it") }
                    parsed.optString("flags").takeIf { it.isNotBlank() }?.let { add("flags=$it") }
                    parsed.optString("missing").takeIf { it.isNotBlank() }?.let { add("missing=$it") }
                    if (parsed.has("actual_policy")) add("actualPolicy=${parsed.opt("actual_policy")}")
                    parsed.optString("error_type").takeIf { it.isNotBlank() }?.let { add("errorType=$it") }
                    parsed.optString("type").takeIf { it.isNotBlank() }?.let { add("type=$it") }
                    if (parsed.has("uid")) add("uid=${parsed.opt("uid")}")
                }
                if (parts.isNotEmpty()) {
                    return "Virtual display bridge rejected: ${parts.joinToString(" ")}"
                }
            }
            return "Virtual display bridge rejected: awaited=$awaited displayId=$resolvedDisplayId disconnected=$disconnected alive=$alive"
        }
    }
}
