package io.github.mangi.eta.agent.runtime

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/** Serialize accept/flush/seal on the same recorder monitor. Registry snapshots hold no lock.
 * The writer runs under this monitor, never with a global registry lock held: flushing recorder A
 * cannot hold a registry lock while waiting for recorder B, or invert the Store lock order.
 */
internal class AgentRunCheckpointRecorder private constructor(
    private val runId: String,
    private val nanoTime: () -> Long,
    private val writeEvent: (Int, AgentEvent) -> Unit,
    private val removeCheckpoint: () -> Unit,
) {
    private var nextSortIndex = 0
    private var pendingDelta: AgentEvent.AssistantBlockDelta? = null
    private var lastFlushNanos = nanoTime()
    private var terminal = false

    @Synchronized
    fun accept(event: AgentEvent) {
        if (terminal) return
        val checkpointEvent = event.recoveryProjection() ?: return
        if (checkpointEvent is AgentEvent.AssistantBlockDelta) {
            val pending = pendingDelta
            if (pending != null && pending.round == checkpointEvent.round &&
                pending.kind == checkpointEvent.kind && pending.index == checkpointEvent.index) {
                pendingDelta = pending.copy(
                    deltaChars = pending.deltaChars + checkpointEvent.deltaChars,
                    delta = pending.delta + checkpointEvent.delta,
                )
            } else {
                flushPendingDelta()
                pendingDelta = checkpointEvent
            }
            if (pendingDelta.orEmptyChars() >= MAX_BUFFERED_DELTA_CHARS ||
                nanoTime() - lastFlushNanos >= MAX_BUFFERED_DELTA_NANOS) flushPendingDelta()
            return
        }
        flushPendingDelta()
        append(checkpointEvent)
    }

    /** Nonterminal: can be called repeatedly during freeze/recovery without losing registration. */
    @Synchronized
    fun flush() {
        if (!terminal) flushPendingDelta()
    }

    /** Only successful terminal sealing unregisters. Failed writes remain buffered and retryable. */
    @Synchronized
    fun seal() {
        if (terminal) return
        flushPendingDelta()
        terminal = true
        active.remove(runId, this)
    }

    @Synchronized
    fun discard() {
        if (terminal) return
        removeCheckpoint()
        pendingDelta = null
        terminal = true
        active.remove(runId, this)
    }

    private fun flushPendingDelta() {
        val event = pendingDelta ?: return
        append(event)
        // Clear/increment only after persistence succeeds: freeze must not swallow failed writes.
        pendingDelta = null
        lastFlushNanos = nanoTime()
    }

    private fun append(event: AgentEvent) {
        writeEvent(nextSortIndex, event)
        nextSortIndex++
    }

    private fun AgentEvent.AssistantBlockDelta?.orEmptyChars(): Int = this?.deltaChars ?: 0

    companion object {
        private const val MAX_BUFFERED_DELTA_CHARS = 512
        private const val MAX_BUFFERED_DELTA_NANOS = 250_000_000L
        private val active = ConcurrentHashMap<String, AgentRunCheckpointRecorder>()

        data class FlushResult(val flushed: Int, val failed: Int)

        /** Snapshot first; never call recorder code from inside a map compute/registry lock. */
        fun flushActive(): FlushResult {
            var flushed = 0
            var failed = 0
            active.values.toList().forEach { recorder ->
                try { recorder.flush(); flushed++ } catch (_: Exception) { failed++ }
            }
            return FlushResult(flushed, failed)
        }

        /** Old callers must no longer terminate active writers merely to preserve data. */
        @Deprecated("Use nonterminal flushActive", ReplaceWith("flushActive().flushed"))
        fun sealAllActive(): Int = flushActive().flushed

        fun create(
            context: Context,
            request: AgentRuntimeWire.RunRequest,
            nanoTime: () -> Long = System::nanoTime,
        ): AgentRunCheckpointRecorder? {
            if (!AgentRunCheckpointStore.start(context, request)) return null
            val app = context.applicationContext
            return register(request.runId, nanoTime,
                writeEvent = { index, event -> AgentRunCheckpointStore.append(app, request.runId, index, event) },
                removeCheckpoint = { AgentRunCheckpointStore.remove(app, request.runId) },
            )
        }

        /** The same recorder/registry/locking implementation with an injected persistence sink. */
        internal fun register(
            runId: String,
            nanoTime: () -> Long = System::nanoTime,
            writeEvent: (Int, AgentEvent) -> Unit,
            removeCheckpoint: () -> Unit = {},
        ): AgentRunCheckpointRecorder = AgentRunCheckpointRecorder(
            runId, nanoTime, writeEvent, removeCheckpoint,
        ).also { active[runId] = it }
    }
}
