package io.github.mangi.eta.agent.virtualdisplay

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.view.Display
import android.view.Surface
import io.github.mangi.eta.agent.accessibility.AccessibilityProtectionProtocol
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.exitProcess
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * 进程内 Surface 临时登记表。
 *
 * 当设备仅具 Root 而未挂载 system_server Hook 时，Eta 主进程将 ImageReader.surface
 * 以一次性随机 token 放入此表；特权子进程（UID 0 / 1000）通过受保护的 ContentProvider
 * 取出 Parcelable Surface 并在系统权限下创建受信任 VirtualDisplay。
 */
internal object VirtualDisplaySurfaceRegistry {
    private data class Entry(
        val surface: Surface,
        val createdAtMs: Long = System.currentTimeMillis(),
    )

    private val entries = ConcurrentHashMap<String, Entry>()
    private const val TOKEN_TTL_MS = 15_000L

    fun register(surface: Surface): String {
        pruneExpired()
        val token = UUID.randomUUID().toString()
        entries[token] = Entry(surface)
        return token
    }

    fun consume(token: String): Surface? {
        pruneExpired()
        val entry = entries.remove(token) ?: return null
        if (System.currentTimeMillis() - entry.createdAtMs > TOKEN_TTL_MS) return null
        return entry.surface.takeIf { it.isValid }
    }

    fun unregister(token: String) {
        entries.remove(token)
    }

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        entries.entries.removeIf { (_, entry) -> now - entry.createdAtMs > TOKEN_TTL_MS }
    }
}

/** Move-only privileged bridge. Legacy hold_display is rejected; only the unified
 * RootDisplayCommandMain may create a display. Movement matches the exact non-main display,
 * never an unrelated task by package name and never launches a replacement on main.
 */
internal object VirtualDisplayCommandMain {
    const val READY_PREFIX = "ETA_VD_READY:"
    const val RESULT_PREFIX = "ETA_VD_RESULT:"

    // 静态强引用持有 VirtualDisplay 与 Surface，防止 ART 局部栈帧优化在等待期间提前触发 GC finalize 销毁虚拟屏。
    @Volatile
    private var retainedVirtualDisplay: android.hardware.display.VirtualDisplay? = null

    @Volatile
    private var retainedSurface: Surface? = null

    @JvmStatic
    @Suppress("DEPRECATION")
    fun main(args: Array<String>) {
        try {
            val uid = Process.myUid()
            if (uid != Process.ROOT_UID && uid != Process.SYSTEM_UID) {
                println(RESULT_PREFIX + JSONObject().put("ok", false).put("code", "ROOT_REQUIRED"))
                exitProcess(1)
            }
            exemptHiddenApis()
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val reader = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
            val firstLine = reader.readLine()?.trim().orEmpty()
            if (firstLine.isEmpty()) exitProcess(1)
            val request = JSONObject(firstLine)
            when (request.optString("action")) {
                "hold_display" -> {
                    println(RESULT_PREFIX + JSONObject().put("ok", false).put("code", "UNIFIED_CANVAS_ONLY"))
                    exitProcess(1)
                }
                "move_to_main" -> {
                    val result = moveTaskToMainDisplay(request)
                    println(RESULT_PREFIX + result.toString())
                    exitProcess(0)
                }
                else -> {
                    println(RESULT_PREFIX + JSONObject().put("ok", false).put("code", "UNKNOWN_ACTION"))
                    exitProcess(1)
                }
            }
        } catch (t: Throwable) {
            println(
                RESULT_PREFIX + JSONObject()
                    .put("ok", false)
                    .put("code", "BRIDGE_ERROR")
                    .put("type", t.javaClass.simpleName),
            )
            exitProcess(1)
        }
    }

    private fun exemptHiddenApis() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/view/",
                    "Landroid/hardware/display/",
                    "Landroid/app/",
                )
            }
        }
    }

    private fun moveTaskToMainDisplay(request: JSONObject): JSONObject =
        io.github.mangi.eta.agent.display.CanvasTaskHandoff.move(request.optInt("display_id", Display.INVALID_DISPLAY))
}
