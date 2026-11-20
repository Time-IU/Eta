package io.github.mangi.eta.agent.display

/** Shared production guards. A backend result is not evidence until these identity/freshness
 * predicates pass; tests deliberately exercise rejected old identities/frames/tasks too. */
internal object CanvasReviewSafety {
    fun inputError(owner: String, run: String, session: String, generation: Long,
        actualOwner: String?, actualRun: String?, actualSession: String?, actualGeneration: Long?,
        cancelled: Boolean, gateOpen: Boolean, userControlling: Boolean): String? = when {
        cancelled -> "DISPLAY_CANCELLED"
        !gateOpen || userControlling -> "USER_TAKEOVER"
        owner != actualOwner || run != actualRun || session != actualSession -> "DISPLAY_RUN_MISMATCH"
        generation != actualGeneration -> "STALE_OBSERVATION"
        else -> null
    }
    fun <T> dispatchInput(authorize: () -> String?, write: () -> T): T {
        val denial = authorize()
        require(denial == null) { denial ?: "DISPLAY_RUN_MISMATCH" }
        return write()
    }

    fun freshFrame(id: Long, capturedAt: Long, generation: Long, afterId: Long,
        notBefore: Long, expectedGeneration: Long, now: Long): Boolean =
        id > afterId && capturedAt >= notBefore && capturedAt <= now && now - capturedAt <= 1500L && generation == expectedGeneration

    fun captureElapsed(imageTimestampNanos: Long, monotonicNanos: Long, elapsedMs: Long): Long {
        val ageNanos = monotonicNanos - imageTimestampNanos
        return if (imageTimestampNanos <= 0L || ageNanos < 0L || ageNanos > 1_500_000_000L) -1L
        else elapsedMs - ageNanos / 1_000_000L
    }

    data class Task(val id: Int, val display: Int, val visible: Boolean)
    fun currentTask(tasks: List<Task>, display: Int): Task? =
        tasks.firstOrNull { it.display == display && it.visible && it.id > 0 }
    fun migrated(task: Int, tasks: List<Task>): Boolean =
        tasks.any { it.id == task && it.display == 0 && it.visible }

    data class Ime(val known: Boolean, val editableFocused: Boolean, val manualRequested: Boolean) {
        val bridgeRequested: Boolean get() = known && editableFocused && manualRequested
    }
}
