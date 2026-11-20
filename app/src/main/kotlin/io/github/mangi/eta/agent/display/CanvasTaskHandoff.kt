package io.github.mangi.eta.agent.display

import android.app.TaskInfo
import android.os.SystemClock
import android.view.Display
import org.json.JSONObject
import org.lsposed.hiddenapibypass.HiddenApiBypass

/** Exact task identity only. A package/launcher match is never a migration receipt. */
internal object CanvasTaskHandoff {
    fun move(displayId: Int): JSONObject = runCatching {
        require(displayId > 0) { "INVALID_DISPLAY" }
        HiddenApiBypass.addHiddenApiExemptions("Landroid/app/ActivityTaskManager", "Landroid/app/IActivityTaskManager")
        val type = Class.forName("android.app.ActivityTaskManager")
        val service = type.getMethod("getService").invoke(null) ?: error("ATM_UNAVAILABLE")
        val rootTaskIds = mutableMapOf<Int, Int>()
        fun tasks(): List<CanvasReviewSafety.Task> {
            val get = service.javaClass.methods.firstOrNull { it.name == "getTasks" && it.parameterCount in setOf(1, 3, 4) }
                ?: error("TASK_QUERY_UNAVAILABLE")
            val raw = when (get.parameterCount) {
                1 -> get.invoke(service, 60)
                3 -> get.invoke(service, 60, false, false)
                else -> get.invoke(service, 60, false, false, Display.INVALID_DISPLAY)
            } as? List<*> ?: error("TASK_QUERY_UNAVAILABLE")
            return raw.filterIsInstance<TaskInfo>().map { task ->
                val display = TaskInfo::class.java.getField("displayId").getInt(task)
                // Unknown visibility is not "visible". No last-in-list guess or package fallback.
                val visible = runCatching { TaskInfo::class.java.getField("isVisible").getBoolean(task) }.getOrDefault(false)
                val root = runCatching { TaskInfo::class.java.getField("rootTaskId").getInt(task) }.getOrElse {
                    val parent = runCatching { TaskInfo::class.java.getField("parentTaskId").getInt(task) }.getOrDefault(Int.MIN_VALUE)
                    if (parent == -1) task.taskId else -1
                }
                rootTaskIds[task.taskId] = root
                CanvasReviewSafety.Task(task.taskId, display, visible)
            }
        }
        val task = CanvasReviewSafety.currentTask(tasks(), displayId) ?: error("VISIBLE_TASK_UNAVAILABLE")
        // Re-read before side effect to avoid moving a task that has already left this display.
        require(CanvasReviewSafety.currentTask(tasks(), displayId)?.id == task.id) { "HANDOFF_TASK_CHANGED" }
        val rootTaskId = rootTaskIds[task.id]?.takeIf { it > 0 } ?: error("ROOT_TASK_IDENTITY_UNAVAILABLE")
        service.javaClass.getMethod("moveRootTaskToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(service, rootTaskId, Display.DEFAULT_DISPLAY)
        val until = SystemClock.elapsedRealtime() + 1800L
        while (SystemClock.elapsedRealtime() < until) {
            if (CanvasReviewSafety.migrated(task.id, tasks())) return@runCatching JSONObject()
                .put("ok", true).put("task_id", task.id).put("source_display_id", displayId)
                .put("target_display_id", Display.DEFAULT_DISPLAY).put("verified", true)
            Thread.sleep(40L)
        }
        error("HANDOFF_UNCONFIRMED")
    }.getOrElse { error -> JSONObject().put("ok", false).put("verified", false)
        .put("code", error.message?.takeIf { it.matches(Regex("[A-Z_]+")) } ?: "HANDOFF_UNAVAILABLE")
        .put("requires_observation", true) }
}
