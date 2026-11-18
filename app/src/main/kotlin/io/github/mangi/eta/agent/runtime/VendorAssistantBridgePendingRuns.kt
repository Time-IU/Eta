package io.github.mangi.eta.agent.runtime

/** Cancellation covers preparation, connection acquisition and actual START enqueueing. */
internal class VendorAssistantBridgePendingRuns {
    class Run internal constructor(val id: String) {
        internal var cancelled = false
        internal var cancelRuntime: (() -> Unit)? = null
    }

    private val lock = Any()
    private val runs = mutableMapOf<String, Run>()
    private var destroyed = false

    fun register(id: String): Run = synchronized(lock) {
        check(!destroyed) { "Vendor bridge destroyed" }
        require(id !in runs) { "Duplicate vendor run id" }
        Run(id).also { runs[id] = it }
    }

    /**
     * enqueueStart MUST only enqueue START, never acquire a connection or await a result.
     * Both sends share this lock and the SAME Messenger: START enters Runtime's single
     * Handler queue before CANCEL, even if cancellation races a delayed connection.
     */
    fun start(run: Run, cancelRuntime: () -> Unit, enqueueStart: () -> Unit): Boolean = synchronized(lock) {
        if (destroyed || run.cancelled || run.cancelRuntime != null || runs[run.id] !== run) return false
        run.cancelRuntime = cancelRuntime
        try {
            enqueueStart()
        } catch (failure: Throwable) {
            // A send failure can be ambiguous: revoke any START that may already be queued.
            cancelLocked(run)
            throw failure
        }
        true
    }

    fun cancel(id: String): Boolean = synchronized(lock) {
        val run = runs[id] ?: return false
        cancelLocked(run)
        true
    }

    private fun cancelLocked(run: Run) {
        if (run.cancelled) return
        run.cancelled = true
        run.cancelRuntime?.let { cancel -> runCatching(cancel) }
    }

    fun isCancelled(run: Run): Boolean = synchronized(lock) {
        destroyed || run.cancelled || runs[run.id] !== run
    }

    fun finish(run: Run) = synchronized(lock) {
        // A stale worker must never remove the registration belonging to a new worker.
        if (runs[run.id] === run) runs.remove(run.id)
        Unit
    }

    fun destroy() = synchronized(lock) {
        destroyed = true
        runs.values.forEach(::cancelLocked)
        // Keep entries until worker finally blocks release their request descriptors.
    }
}
