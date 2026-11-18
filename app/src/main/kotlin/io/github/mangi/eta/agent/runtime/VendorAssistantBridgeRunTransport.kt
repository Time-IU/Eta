package io.github.mangi.eta.agent.runtime

import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.concurrent.CountDownLatch

/** Bridge-local transport: the cancellation gate surrounds the real START send, not run(). */
internal object VendorAssistantBridgeRunTransport {
    fun run(
        context: Context,
        request: AgentRuntimeWire.RunRequest,
        pending: VendorAssistantBridgePendingRuns,
        run: VendorAssistantBridgePendingRuns.Run,
        onEvent: (AgentEvent) -> Unit,
    ): AgentRuntimeWire.RunResult {
        fun cancelled() = AgentRuntimeWire.RunResult(request.runId, false, "", "厂商助手请求已取消")
        if (pending.isCancelled(run)) return cancelled()
        val lease = AgentRuntimeConnection.acquire(context, AndroidAgentLogger)
            ?: return AgentRuntimeWire.RunResult(request.runId, false, "", "Agent Runtime 服务绑定失败")
        val completed = CountDownLatch(1)
        val mailbox = AgentResultMailbox()
        val images = java.util.concurrent.atomic.AtomicReference<AgentRuntimeImageTransfer.PreparedImages?>()
        val receiver = Messenger(object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    AgentRuntimeWire.MSG_REQUEST_INGESTED -> images.getAndSet(null)?.close()
                    AgentRuntimeWire.MSG_EVENT -> if (!pending.isCancelled(run)) {
                        AgentRuntimeWire.eventFromBundle(msg.data)?.let(onEvent)
                    }
                    AgentRuntimeWire.MSG_RESULT -> {
                        mailbox.set(msg.data)
                        completed.countDown()
                    }
                }
            }
        })
        val death = IBinder.DeathRecipient { completed.countDown() }
        try {
            lease.binder.linkToDeath(death, 0)
            if (pending.isCancelled(run)) return cancelled()
            val prepared = AgentRuntimeImageTransfer.prepare(context, request.images)
            images.set(prepared)
            val data = AgentRuntimeWire.toBundle(request, prepared.images, context.cacheDir)
            val started = try {
                pending.start(run, cancelRuntime = {
                    // Same Messenger/queue as START. Never reacquire asynchronously here.
                    lease.messenger.send(Message.obtain(null, AgentRuntimeWire.MSG_CANCEL).apply {
                        this.data = AgentRuntimeWire.ackBundle(request.runId)
                    })
                }, enqueueStart = {
                    AgentWireText.send(lease.messenger, Message.obtain(null, AgentRuntimeWire.MSG_START_RUN).apply {
                        replyTo = receiver
                        this.data = data
                    })
                })
            } finally {
                // If the gate refused START, AgentWireText.send did not consume text FDs.
                AgentWireText.close(data)
            }
            if (!started) return cancelled()
            try {
                completed.await()
            } catch (interrupted: InterruptedException) {
                pending.cancel(run.id)
                Thread.currentThread().interrupt()
                return cancelled()
            }
            return mailbox.get()?.let(AgentRuntimeWire::runResultFromBundle)
                ?: AgentRuntimeWire.RunResult(request.runId, false, "", "Agent Runtime 服务连接已断开")
        } finally {
            mailbox.close()
            images.getAndSet(null)?.close()
            runCatching { lease.binder.unlinkToDeath(death, 0) }
            lease.close()
        }
    }
}
