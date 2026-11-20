package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentMemoryWorkGate
import io.github.mangi.eta.agent.runtime.AgentRunController

/** Preserve the delegate's protocol/capabilities. Every call (including retries and compaction)
 * is admitted independently, before its HTTP/native work, not just at run creation.
 */
internal class AgentMemoryBoundProvider(
    private val delegate: AgentProviderClient,
    private val memory: AgentMemoryWorkGate,
) : AgentProviderClient by delegate {
    override fun complete(
        request: ProviderRequest,
        runController: AgentRunController,
        onEvent: (ProviderEvent) -> Unit,
    ): ProviderResponse {
        memory.await(runController)
        return delegate.complete(request, runController) { event ->
            // An issued request is not cancelled/replayed to implement memory pressure. At FREEZE
            // stop consuming further chunks until recovery; checkpoint flushing runs separately.
            memory.await(runController, AgentMemoryWorkGate.Boundary.LIGHT_TOOL)
            onEvent(event)
        }
    }
}
