package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ReasoningEffort

/** A vendor may supply conversational input, never a provider, system prompt or tool grant. */
internal object VendorAssistantBridgeRequest {
    fun prepare(
        request: AgentRuntimeWire.RunRequest,
        source: String,
        uid: Int,
        runtimeConfig: AgentModelClient.ModelConfig,
        permissions: AgentRuntimePolicy.Permissions = AgentRuntimePolicy.permissions(Prefs.localAgentPreferences()),
    ): AgentRuntimeWire.RunRequest {
        require(source == "breeno" || source == "xiaoai")
        require(request.operation == AgentRuntimeWire.OP_CHAT)
        val handoff = requireNotNull(request.handoff)
        require(handoff.source == source)
        val payload = requireNotNull(AgentExternalArchivePayload.from(handoff.payload))
        val id = VendorAssistantBridgePolicy.runId(source, uid, request.runId)
        val sessionId = VendorAssistantBridgePolicy.runId(source, uid, payload.conversationKey)
        // Breeno's native deep-thinking selector is a presentation/model preference, not a grant.
        val enabled = payload.thinkingEnabled ?: Prefs.isEnabled(Prefs.Keys.AGENT_THINKING_ENABLED)
        val desiredEffort = if (payload.thinkingEnabled != null) {
            ReasoningEffort.fromLegacy(enabled)
        } else if (enabled) runtimeConfig.effectiveReasoningEffort else ReasoningEffort.OFF
        val effort = runtimeConfig.reasoningCapabilities?.normalize(desiredEffort)
            ?: if (enabled) runtimeConfig.effectiveReasoningEffort else ReasoningEffort.OFF
        val config = AgentRuntimePolicy.constrain(
            runtimeConfig.copy(
                supportsSearchSuggestions = false,
                terminalTools = permissions.terminalTools,
                browserTools = permissions.browserTools,
                deviceDirectTools = permissions.deviceDirectTools,
                deviceSensitiveReadTools = permissions.deviceSensitiveReadTools,
                deviceSensitiveActionTools = permissions.deviceSensitiveActionTools,
                subagentTools = permissions.subagentTools,
                autoCompactionEnabled = runtimeConfig.autoCompactionEnabled && permissions.autoCompaction,
                thinkingEnabled = effort.enablesReasoning,
                reasoningEffort = effort,
            ), permissions,
        )
        return request.copy(
            runId = id,
            modelSessionId = sessionId,
            operation = AgentRuntimeWire.OP_CHAT,
            rewriteTargetMessageId = null,
            assistantScreenContext = "",
            config = config,
            history = request.history.filter { it.role in setOf("user", "assistant", "tool") },
            handoff = handoff.copy(
                id = id,
                source = source,
                dismissEntrySurfaceOnForegroundOperation = true,
                payload = payload.copy(
                    userText = request.prompt,
                    // Keep the published archive identity and the original vendor dialog grouping.
                    conversationKey = payload.conversationKey,
                    thinkingEnabled = config.effectiveReasoningEffort.enablesReasoning,
                    reasoningEffort = config.effectiveReasoningEffort,
                ).toJson(),
            ),
        )
    }
}
