package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentMemoryGate
import io.github.mangi.eta.agent.runtime.AgentMemoryWorkGate
import io.github.mangi.eta.agent.runtime.AgentRunController
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentMemoryBoundProviderTest {
    private fun request(purpose: ProviderRequestPurpose) = ProviderRequest(
        AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test", model = "test", systemPrompt = ""),
        JSONArray(), JSONArray(), purpose = purpose,
    )

    @Test fun normalAndCompactionRequestsWaitAtQueueOnEveryAttempt() {
        val tier = AtomicInteger(AgentMemoryGate.TIER_QUEUE)
        var calls = 0
        var waits = 0
        val delegate = object : AgentProviderClient {
            override val id = "fake"
            override val capabilities = ProviderCapabilities(EndpointKind.RESPONSES, true, true, true, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                assertEquals(AgentMemoryGate.TIER_NORMAL, tier.get())
                calls++
                return ProviderResponse(JSONObject().put("content", "done").put("finish_reason", "stop"))
            }
        }
        val gate = AgentMemoryWorkGate(tier::get, {}, {
            assertEquals(waits, calls)
            waits++
            tier.set(AgentMemoryGate.TIER_NORMAL)
        })
        val provider = AgentMemoryBoundProvider(delegate, gate)
        for (purpose in listOf(ProviderRequestPurpose.CHAT, ProviderRequestPurpose.CHAT, ProviderRequestPurpose.COMPACTION)) {
            tier.set(AgentMemoryGate.TIER_QUEUE)
            provider.complete(request(purpose), AgentRunController()) {}
        }
        assertEquals(3, calls)
        assertEquals(3, waits)
        assertEquals(delegate.capabilities, provider.capabilities)
    }

    @Test fun twoInFlightFreezesStopEventDeliveryWithoutReissuingRequest() {
        val tier = AtomicInteger(AgentMemoryGate.TIER_NORMAL)
        var calls = 0
        var delivered = 0
        var flushes = 0
        val delegate = object : AgentProviderClient {
            override val id = "fake"
            override val capabilities = ProviderCapabilities(EndpointKind.RESPONSES, true, true, true, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                calls++
                repeat(2) {
                    tier.set(AgentMemoryGate.TIER_FREEZE)
                    onEvent(ProviderEvent.RequestStarted)
                    assertEquals(it + 1, delivered)
                }
                return ProviderResponse(JSONObject().put("content", "done").put("finish_reason", "stop"))
            }
        }
        val gate = AgentMemoryWorkGate(tier::get, { flushes++ }, {
            assertEquals(flushes - 1, delivered)
            tier.set(AgentMemoryGate.TIER_NORMAL)
        })
        AgentMemoryBoundProvider(delegate, gate).complete(request(ProviderRequestPurpose.CHAT), AgentRunController()) {
            assertEquals(AgentMemoryGate.TIER_NORMAL, tier.get())
            delivered++
        }
        assertEquals(1, calls)
        assertEquals(2, delivered)
        assertEquals(2, flushes)
    }
}
