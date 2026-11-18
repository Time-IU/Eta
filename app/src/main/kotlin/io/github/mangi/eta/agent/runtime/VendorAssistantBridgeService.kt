package io.github.mangi.eta.agent.runtime

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * The ONLY exported vendor IPC. AgentRuntimeService stays unexported and same-UID-only.
 *
 * Authenticate the kernel-supplied Messenger.sendingUid on EVERY command (not onBind,
 * which Android dispatches as the service). Accept only same-user preinstalled vendor
 * assistants signed by the platform, a preinstalled vendor trust anchor, or
 * their matching factory APK on an immutable system partition. Android's
 * package installer enforces the same signing lineage for updated system packages.
 * Unknown ROM signing arrangements fail closed; there is no package-name-only fallback.
 *
 * No raw Runtime Binder, credentials, first-party results, global run enumeration,
 * arbitrary provider configuration, compaction, attach or steering is exposed.
 */
internal class VendorAssistantBridgeService : Service() {
    private val pendingRuns = VendorAssistantBridgePendingRuns()
    private val control = ThreadPoolExecutor(
        1, 1, 30, TimeUnit.SECONDS, ArrayBlockingQueue(32),
    ) { task -> Thread(task, "vendor-bridge-control") }
    private val workers = ThreadPoolExecutor(
        0, 4, 60, TimeUnit.SECONDS, SynchronousQueue(),
    ) { task -> Thread(task, "vendor-bridge-run") }
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (VendorAssistantBridgeCaller.candidateSource(packageManager, msg.sendingUid, Process.myUid()) == null) {
                closeRequest(msg.what, msg.data)
                return
            }
            // Message objects are recycled after this handler returns; retain only fields.
            val what = msg.what
            val data = msg.data
            val uid = msg.sendingUid
            val reply = msg.replyTo
            try {
                control.execute {
                    try {
                        // Certificate/factory-APK verification can do disk I/O; never block the main looper.
                        val source = VendorAssistantBridgeCaller.source(packageManager, uid, Process.myUid())
                        if (source == null) {
                            closeRequest(what, data)
                            if (what == AgentRuntimeWire.MSG_START_RUN) sendFailure(reply, "", "厂商助手签名验证失败")
                            return@execute
                        }
                        dispatch(source, uid, what, data, reply)
                    } catch (failure: Exception) {
                        closeRequest(what, data)
                        if (what == AgentRuntimeWire.MSG_START_RUN || what == AgentRuntimeWire.MSG_READ_CONTEXT_RESULT) {
                            sendFailure(reply, "", "厂商助手桥接请求被拒绝或暂不可用")
                        }
                        AndroidAgentLogger.warnThrottled("vendor_bridge_request_rejected") {
                            "Vendor bridge rejected request: type=${failure.safeLogType()}"
                        }
                    }
                }
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                closeRequest(what, data)
                sendFailure(reply, "", "厂商助手桥接繁忙，请稍后重试")
            }
        }
    })

    override fun onBind(intent: Intent): IBinder? =
        messenger.binder.takeIf { intent.action == ACTION_BIND }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopSelf(startId)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Revoke preparation and already-enqueued runs before draining queued messages.
        // Pending registration/START/CANCEL share one gate; queued STARTs fail closed.
        pendingRuns.destroy()
        control.shutdown()
        workers.shutdown()
        super.onDestroy()
    }

    private fun dispatch(source: String, uid: Int, what: Int, data: Bundle, reply: Messenger?) {
        when (what) {
            AgentRuntimeWire.MSG_START_RUN -> startRun(source, uid, data, reply)
            AgentRuntimeWire.MSG_CANCEL -> {
                val id = VendorAssistantBridgePolicy.runId(source, uid, AgentRuntimeWire.runIdFromBundle(data))
                if (!pendingRuns.cancel(id)) {
                    // Recovery after a bridge restart: no local worker can start this id later.
                    AgentRuntimeClient(this, AndroidAgentLogger).cancelRun(id)
                }
            }
            AgentRuntimeWire.MSG_ACK_RESULT -> {
                val id = AgentRuntimeWire.runIdFromBundle(data)
                val item = AgentRuntimeResultStore.list(this).firstOrNull {
                    it.result.runId == id && ownsResult(source, uid, it)
                }
                if (item != null) AgentRuntimeResultStore.remove(this, id)
            }
            AgentRuntimeWire.MSG_DRAIN_RESULTS -> {
                val pending = AgentRuntimeResultStore.list(this)
                    .filter { ownsResult(source, uid, it) }
                    .take(8)
                    .map { item -> item.copy(result = AgentRuntimeWire.RunResult(
                        item.result.runId, item.result.ok, "", contextSnapshotRef = item.result.runId,
                    )) }
                send(reply, AgentRuntimeWire.MSG_DRAIN_RESULTS_RESPONSE, AgentRuntimeWire.completedRunsToBundle(pending))
            }
            AgentRuntimeWire.MSG_READ_CONTEXT_RESULT -> {
                val id = AgentRuntimeWire.runIdFromBundle(data)
                val item = AgentRuntimeResultStore.readOwned(this, id, data.getString("context_owner").orEmpty())
                require(item != null && ownsResult(source, uid, item))
                send(reply, AgentRuntimeWire.MSG_RESULT, AgentRuntimeWire.toBundle(item.result, cacheDir))
            }
            else -> closeRequest(what, data) // No Runtime control/enumeration API passthrough.
        }
    }

    private fun ownsResult(source: String, uid: Int, item: AgentRuntimeWire.CompletedRun): Boolean =
        item.handoff.source == source && VendorAssistantBridgePolicy.ownsResultId(source, uid, item.result.runId)

    private fun startRun(source: String, uid: Int, data: Bundle, reply: Messenger?) {
        require(reply != null)
        val incoming = AgentRuntimeWire.incomingRunRequestFromBundle(data)
        var registration: VendorAssistantBridgePendingRuns.Run? = null
        try {
            val request = incoming.request
            require(request.operation == AgentRuntimeWire.OP_CHAT)
            require(request.handoff?.source == source)
            val id = VendorAssistantBridgePolicy.runId(source, uid, request.runId)
            // Register before submitting work: cancellation must also cover queued/preparing requests.
            val pending = pendingRuns.register(id)
            registration = pending
            workers.execute {
                val result = try { runCatching {
                    check(!pendingRuns.isCancelled(pending)) { "Vendor request cancelled" }
                    val materialized = AgentRuntimeImageTransfer.materialize(incoming)
                    send(reply, AgentRuntimeWire.MSG_REQUEST_INGESTED, AgentRuntimeWire.ackBundle(id))
                    check(!pendingRuns.isCancelled(pending)) { "Vendor request cancelled" }
                    val admitted = VendorAssistantBridgeRequest.prepare(materialized, source, uid, runBlocking {
                        // Preserve the published vendor behavior: the global model, not Eta voice overlay overrides.
                        RuntimeConfigRepository.currentRuntimeConfig()
                    } ?: error("No configured assistant model"))
                    VendorAssistantBridgeRunTransport.run(this, admitted, pendingRuns, pending) { event ->
                        send(reply, AgentRuntimeWire.MSG_EVENT, AgentRuntimeWire.eventToBundle(event))
                    }
                }.getOrElse { failure ->
                    incoming.close()
                    AndroidAgentLogger.warnThrottled("vendor_bridge_run_failed") {
                        "Vendor bridge run failed: type=${failure.safeLogType()}"
                    }
                    AgentRuntimeWire.RunResult(id, false, "", if (pendingRuns.isCancelled(pending)) {
                        "厂商助手请求已取消"
                    } else "厂商助手请求失败，请检查 Eta 默认模型及桥接日志")
                } } finally {
                    incoming.close()
                    pendingRuns.finish(pending)
                }
                send(reply, AgentRuntimeWire.MSG_RESULT, AgentRuntimeWire.toBundle(result, cacheDir))
            }
        } catch (failure: Exception) {
            registration?.let(pendingRuns::finish)
            incoming.close()
            throw failure
        }
    }

    private fun send(target: Messenger?, what: Int, data: Bundle) {
        runCatching { AgentWireText.send(target, Message.obtain(null, what).apply { this.data = data }) }
    }

    private fun sendFailure(target: Messenger?, id: String, message: String) =
        send(target, AgentRuntimeWire.MSG_RESULT, AgentRuntimeWire.toBundle(AgentRuntimeWire.RunResult(id, false, "", message)))

    private fun closeRequest(what: Int, data: Bundle) {
        // Invalid parcelables must never escape the main Handler, even for rejected senders.
        runCatching {
            if (what == AgentRuntimeWire.MSG_START_RUN) AgentRuntimeWire.closeImageDescriptors(data)
            else AgentWireText.close(data)
        }
    }

    companion object {
        private const val ACTION_BIND = "io.github.mangi.eta.agent.runtime.VENDOR_BIND"
        fun serviceIntent(): Intent = Intent(ACTION_BIND).setComponent(ComponentName(
            "io.github.mangi.eta", "io.github.mangi.eta.agent.runtime.VendorAssistantBridgeService",
        ))
    }
}
