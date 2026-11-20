package io.github.mangi.eta.agent.runtime

/** Memory is an independent admission reason, never a controller pause/resume operation.
 * A healthy sample only releases this wait; an explicit user pause still belongs to the controller.
 * No registry/recorder/GUI lock may be held while awaiting admission.
 */
internal class AgentMemoryWorkGate(
    private val readTier: () -> Int = AgentMemoryGate::tier,
    private val flushActive: () -> Unit = { AgentRunCheckpointRecorder.flushActive() },
    private val waitForSample: (AgentRunController) -> Unit = { it.awaitRetryDelay(POLL_MS) },
) {
    enum class Boundary { REQUEST, HEAVY_TOOL, LIGHT_TOOL }

    fun isBlocked(boundary: Boundary): Boolean = blocks(readTier(), boundary)

    fun await(controller: AgentRunController, boundary: Boundary = Boundary.REQUEST) {
        var flushedForFreeze = false
        while (true) {
            // Cancellation check must not wait for a user's pause before preserving pending data.
            if (controller.isCancelled) throw AgentRunCancelledException()
            val tier = readTier()
            if (tier >= AgentMemoryGate.TIER_FREEZE && !flushedForFreeze) {
                flushActive()
                flushedForFreeze = true
            } else if (tier < AgentMemoryGate.TIER_FREEZE) {
                flushedForFreeze = false
            }
            if (!blocks(tier, boundary)) {
                controller.throwIfCancelled()
                // A user pause may have lasted minutes: sample memory again after it ends.
                if (!blocks(readTier(), boundary)) return
                continue
            }
            waitForSample(controller)
        }
    }

    companion object {
        const val POLL_MS = 500L
        fun blocks(tier: Int, boundary: Boundary): Boolean = when (boundary) {
            Boundary.REQUEST, Boundary.HEAVY_TOOL -> tier >= AgentMemoryGate.TIER_QUEUE
            Boundary.LIGHT_TOOL -> tier >= AgentMemoryGate.TIER_FREEZE
        }

        /** Unknown/MCP tools may allocate arbitrarily; only bounded metadata reads are light. */
        fun boundaryForTool(name: String): Boundary = when (name) {
            "get_current_context", "get_device_state", "device_status", "network_info",
            "skills_list", "skills_read", "stat_file" -> Boundary.LIGHT_TOOL
            else -> Boundary.HEAVY_TOOL
        }
    }
}
