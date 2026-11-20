package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.security.PromptSafetyContract
import io.github.mangi.eta.agent.skill.SkillContext
import org.junit.Assert.*
import org.junit.Test

class PromptContractIntegrationTest {
    @Test fun productionBuilderContainsSharedContractWithoutAdvertisingUnavailablePrivateDataAccess() {
        for (root in listOf(false, true)) {
            val config = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "",
                model = "test", providerId = "contract-test", systemPrompt = "custom identity", terminalTools = true)
            val messages = AgentPromptBuilder.buildSystemMessages(config, SkillContext.EMPTY,
                AgentMemoryContext.DISABLED, root)
            val text = (0 until messages.length()).joinToString("\n") { messages.getJSONObject(it).getString("content") }
            assertEquals("custom identity", messages.getJSONObject(0).getString("content"))
            assertEquals(PromptSafetyContract.Result.CURRENT_VALID,
                PromptSafetyContract.assess(text, PromptSafetyContract.VERSION, true))
            assertEquals(root, text.contains("相关应用私有文件与数据库"))
        }
    }
}
