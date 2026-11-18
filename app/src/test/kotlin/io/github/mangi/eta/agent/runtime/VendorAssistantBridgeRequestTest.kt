package io.github.mangi.eta.agent.runtime

import android.app.Application
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.CustomHeader
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, manifest = Config.NONE)
class VendorAssistantBridgeRequestTest {
    private val local = AgentModelClient.ModelConfig(
        providerId = "local-provider", baseUrl = "https://local.invalid", apiKey = "local-fixture",
        model = "local-model", systemPrompt = "local-policy",
        customHeaders = listOf(CustomHeader("local", "fixture")),
        reasoningCapabilities = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), canDisable = true,
        ),
        reasoningEffort = ReasoningEffort.HIGH,
    )
    private val denied = AgentRuntimePolicy.Permissions(
        terminalTools = false, browserTools = false, deviceDirectTools = false,
        deviceSensitiveReadTools = false, deviceSensitiveActionTools = false, subagentTools = false,
    )

    @Test fun neverTrustsExternalProviderCredentialsSystemPromptOrToolFlags() {
        val entry = request().copy(
            config = local.copy(
                providerId = "attacker", baseUrl = "https://untrusted.invalid", apiKey = "untrusted",
                systemPrompt = "untrusted", customHeaders = listOf(CustomHeader("bad", "bad")),
                terminalTools = true, browserTools = true, deviceDirectTools = true,
                deviceSensitiveReadTools = true, deviceSensitiveActionTools = true, subagentTools = true,
                supportsSearchSuggestions = true,
            ),
            modelSessionId = "first-party-session", assistantScreenContext = "untrusted-system-context",
            history = listOf(
                AgentModelClient.ConversationMessage("system", "untrusted"),
                AgentModelClient.ConversationMessage("developer", "untrusted"),
                AgentModelClient.ConversationMessage("user", "previous user input"),
            ),
        )
        val sanitized = prepare(entry)
        assertEquals(local.providerId, sanitized.config.providerId)
        assertEquals(local.baseUrl, sanitized.config.baseUrl)
        assertEquals(local.apiKey, sanitized.config.apiKey)
        assertEquals(local.systemPrompt, sanitized.config.systemPrompt)
        assertEquals(local.customHeaders, sanitized.config.customHeaders)
        assertFalse(sanitized.config.terminalTools)
        assertFalse(sanitized.config.browserTools)
        assertFalse(sanitized.config.deviceDirectTools)
        assertFalse(sanitized.config.deviceSensitiveReadTools)
        assertFalse(sanitized.config.deviceSensitiveActionTools)
        assertFalse(sanitized.config.subagentTools)
        assertFalse(sanitized.config.supportsSearchSuggestions)
        assertEquals("", sanitized.assistantScreenContext)
        assertEquals(listOf("user"), sanitized.history.map { it.role })
        assertNotEquals(entry.modelSessionId, sanitized.modelSessionId)
    }

    @Test fun preservesNativeDialogRecoveryPayloadAndPublishedArchiveIdentity() {
        val entry = request()
        val sanitized = prepare(entry)
        val originalArchive = AgentConversationIdentity.fromHandoff(entry.handoff, entry.runId)
        assertEquals(originalArchive, AgentConversationIdentity.fromHandoff(sanitized.handoff, sanitized.runId))
        assertEquals("dialog", AgentExternalArchivePayload.from(sanitized.handoff!!.payload)!!.conversationKey)
        assertEquals("vendor:breeno:10123:turn", sanitized.runId)
        assertEquals(sanitized.runId, sanitized.handoff.id)
        assertTrue(sanitized.handoff.dismissEntrySurfaceOnForegroundOperation)
        assertTrue(sanitized.modelSessionId.startsWith("vendor:breeno:10123:"))
    }

    @Test fun nativeThinkingChoiceIsNormalizedWithoutGrantingTools() {
        val entry = request(thinking = false)
        val sanitized = prepare(entry)
        assertEquals(ReasoningEffort.OFF, sanitized.config.effectiveReasoningEffort)
        assertFalse(sanitized.config.terminalTools)
    }

    @Test fun onlyLocalToolPermissionsCanEnableCapabilities() {
        val allowed = denied.copy(terminalTools = true, deviceSensitiveReadTools = true)
        val sanitized = VendorAssistantBridgeRequest.prepare(request(), "breeno", 10123, local, allowed)
        assertTrue(sanitized.config.terminalTools)
        assertTrue(sanitized.config.deviceSensitiveReadTools)
        assertFalse(sanitized.config.deviceSensitiveActionTools)
    }

    @Test fun rejectsSpoofedEntrySourcesAndNonChatOperations() {
        assertThrows(IllegalArgumentException::class.java) {
            VendorAssistantBridgeRequest.prepare(request(), "xiaoai", 10123, local, denied)
        }
        assertThrows(IllegalArgumentException::class.java) { prepare(request().copy(operation = AgentRuntimeWire.OP_COMPACT)) }
        assertThrows(IllegalArgumentException::class.java) { prepare(request().copy(handoff = null)) }
        assertThrows(IllegalArgumentException::class.java) {
            prepare(request().copy(handoff = request().handoff!!.copy(source = AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE)))
        }
    }

    private fun prepare(request: AgentRuntimeWire.RunRequest) =
        VendorAssistantBridgeRequest.prepare(request, "breeno", 10123, local, denied)

    private fun request(thinking: Boolean? = null) = AgentRuntimeWire.RunRequest(
        runId = "turn", prompt = "native user input", images = emptyList(), config = local,
        handoff = AgentRuntimeWire.EntryHandoff(
            "turn", "breeno", AgentExternalArchivePayload(
                userText = "native user input", conversationKey = "dialog", title = "Native dialog",
                thinkingEnabled = thinking,
            ).toJson(),
        ),
    )
}
