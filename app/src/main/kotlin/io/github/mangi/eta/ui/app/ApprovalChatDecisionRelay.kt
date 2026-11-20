package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.display.dashboard.ApprovalDecisionReceipt
import io.github.mangi.eta.agent.display.dashboard.ApprovalDecisionRequest
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable, token-scoped routing. An absent/unknown ACK is never permission to send a second time. */
internal class ApprovalChatDecisionRelay(
    private val journal: Journal,
    private val transport: Transport,
) {
    enum class Phase { Planned, Dispatching, Accepted }
    data class Record(val fingerprint: String, val receivingRun: String, val phase: Phase)
    data class Owner(val run: String, val conversation: String, val accepted: Boolean = true)
    enum class SteerAck { Accepted, Rejected, Unknown }

    interface Journal {
        fun read(token: String): Record?
        /** True means durably committed; false/throw must never be followed by network dispatch. */
        fun save(token: String, record: Record): Boolean
        fun remove(token: String): Boolean
    }
    interface Transport {
        /** Null means the runtime cannot prove its active set. */
        suspend fun active(): List<Owner>?
        suspend fun steer(run: String, conversation: String, text: String): SteerAck
        /**
         * Must persist the original-conversation user row before invoking beforeDispatch, and return
         * true ONLY on a runtime RunStarted event. No selected composer/default-model fallback.
         * beforeDispatch must succeed before the IPC start request, never afterwards.
         */
        suspend fun startOriginalConversation(
            request: ApprovalDecisionRequest,
            receivingRun: String,
            beforeDispatch: () -> Boolean,
        ): Boolean
    }

    suspend fun submit(request: ApprovalDecisionRequest): ApprovalDecisionReceipt? = gate.withLock {
        if (request.token.isBlank() || request.conversation.isBlank() || request.originatingRun.isBlank() || request.text.isBlank()) return@withLock null
        val fingerprint = fingerprint(request)
        val old = journal.read(request.token)
        if (old != null && (old.fingerprint != fingerprint ||
                old.receivingRun !in setOf(request.originatingRun, continuationRun(request)) ||
                (old.phase == Phase.Planned && old.receivingRun == request.originatingRun))) return@withLock null
        fun receipt(run: String) = ApprovalDecisionReceipt(request.token, request.conversation, run)
        if (old?.phase == Phase.Accepted) return@withLock receipt(old.receivingRun)
        val active = transport.active() ?: return@withLock null
        // Even a colliding run ID must not cross the authoritative conversation boundary.
        if (active.any { it.run == request.originatingRun && it.conversation != request.conversation }) return@withLock null
        if (old?.phase == Phase.Dispatching) {
            if (active.any { it.run == old.receivingRun && it.conversation != request.conversation }) return@withLock null
            // New-round ID is deterministic and unique to this exact token/owner/text. Its proven
            // runtime presence recovers a lost start ACK. A steer has no idempotency key: never replay.
            if (old.receivingRun != request.originatingRun && active.count {
                    it.run == old.receivingRun && it.conversation == request.conversation && it.accepted
                } == 1) {
                return@withLock if (journal.save(request.token, old.copy(phase = Phase.Accepted))) receipt(old.receivingRun) else null
            }
            return@withLock null
        }
        val origin = active.filter { it.run == request.originatingRun && it.conversation == request.conversation }
        if (origin.size > 1) return@withLock null
        if (origin.size == 1) {
            if (old != null) return@withLock null // an already planned continuation never changes route
            val attempt = Record(fingerprint, request.originatingRun, Phase.Dispatching)
            if (!journal.save(request.token, attempt)) return@withLock null
            return@withLock when (transport.steer(request.originatingRun, request.conversation, request.text)) {
                SteerAck.Accepted -> if (journal.save(request.token, attempt.copy(phase = Phase.Accepted))) receipt(request.originatingRun) else null
                SteerAck.Rejected -> { journal.remove(request.token); null } // definite negative ACK permits deliberate retry
                SteerAck.Unknown -> null // IPC timeout may already have accepted; preserve token, do not duplicate
            }
        }
        // Another run in the same conversation is NOT an acceptable substitute for the original.
        if (active.any { it.conversation == request.conversation }) return@withLock null
        val target = old?.receivingRun ?: continuationRun(request)
        val plan = Record(fingerprint, target, Phase.Planned)
        if (old == null && !journal.save(request.token, plan)) return@withLock null
        val started = transport.startOriginalConversation(request, target) {
            val current = journal.read(request.token)
            current == plan && journal.save(request.token, plan.copy(phase = Phase.Dispatching))
        }
        // A callback returning true without persisting dispatch cannot fabricate acceptance.
        val dispatched = journal.read(request.token)
        if (!started || dispatched != plan.copy(phase = Phase.Dispatching)) return@withLock null
        return@withLock if (journal.save(request.token, plan.copy(phase = Phase.Accepted))) receipt(target) else null
    }

    companion object Identity {
        // All Activity/ViewModel owners in this process serialize token claims, including rotation.
        private val gate = Mutex()
        fun fingerprint(request: ApprovalDecisionRequest): String = digest(
            listOf(request.token, request.conversation, request.originatingRun, request.text),
        )
        fun continuationRun(request: ApprovalDecisionRequest): String = "approval-" + fingerprint(request)
        fun tokenKey(token: String): String = digest(listOf(token))
        private fun digest(parts: List<String>): String {
            val digest = MessageDigest.getInstance("SHA-256")
            parts.forEach { part ->
                val bytes = part.toByteArray(Charsets.UTF_8)
                digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
                digest.update(':'.code.toByte())
                digest.update(bytes)
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }
    }
}
