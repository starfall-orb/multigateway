package org.starfall.multigateway

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*

class ClaudeThinkingTest {
    private fun parameters(model: String, effort: String?, limit: Int = 16000) =
        ModelConfiguration(supportsThinking = true, reasoningEffort = effort).claudeThinkingParameters(model, limit)

    @Test fun adaptiveThinkingSendsNativeEffortWithoutATokenBudget() {
        val body = parameters("claude-opus-4-6", "medium", 512)
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("adaptive", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("summarized", body["thinking"]!!.jsonObject["display"]!!.jsonPrimitive.content)
        assertFalse(body.toString().contains("budget_tokens"))
    }

    @Test fun modelsExposeOnlyTheirSupportedEffortLevels() {
        assertEquals(listOf("low", "medium", "high"), claudeThinkingCapabilities("claude-opus-4-5-20251101").efforts)
        assertEquals(listOf("low", "medium", "high", "max"), claudeThinkingCapabilities("claude-sonnet-4-6").efforts)
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), claudeThinkingCapabilities("claude-opus-4.7").efforts)
        assertEquals(claudeThinkingCapabilities("claude-opus-4-6"), claudeThinkingCapabilities("claude-4-6-opus"))
        assertTrue(claudeThinkingCapabilities("claude-opus-5-9").efforts.isEmpty())
    }

    @Test fun savedXHighMapsToMaxOnlyForModelsThatSupportMax() {
        assertEquals("max", parameters("claude-opus-4-6", "xhigh")["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("high", parameters("claude-opus-4-5", "max")["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test fun opus45CombinesEffortWithManualThinking() {
        val body = parameters("claude-opus-4-5", "medium")
        assertEquals("medium", body["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        assertEquals("enabled", body["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(2048, body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
    }

    @Test fun legacyAndUnknownAliasesKeepTheirExistingBudgets() {
        for (model in listOf("claude-sonnet-4-5", "claude-haiku-4-5", "custom-claude")) {
            val body = parameters(model, "xhigh", 6000)
            assertFalse(body.containsKey("output_config"))
            assertEquals(5999, body["thinking"]!!.jsonObject["budget_tokens"]!!.jsonPrimitive.int)
            assertTrue(parameters(model, "high", 1024).isEmpty())
        }
    }

    @Test fun offRespectsEachModelsThinkingModes() {
        assertEquals("disabled", parameters("claude-opus-4-6", "none")["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("between_tools", parameters("claude-sonnet-5-5", "none")["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        val alwaysOn = parameters("claude-opus-5-5", "off")
        assertEquals("adaptive", alwaysOn["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("low", alwaysOn["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
    }

    @Test fun defaultAndModelsWithThinkingDisabledInCapabilitiesAreNotOverridden() {
        assertTrue(parameters("claude-opus-4-6", null).isEmpty())
        assertTrue(ModelConfiguration(supportsThinking = false, reasoningEffort = "high")
            .claudeThinkingParameters("claude-opus-4-6", 16000).isEmpty())
    }

    @Test fun samplingDoesNotConflictWithThinking() {
        assertFalse(ModelConfiguration(supportsThinking = true, reasoningEffort = "high")
            .claudeAllowsSampling("claude-opus-4-6", 16000))
        assertFalse(ModelConfiguration(supportsThinking = true, reasoningEffort = "high")
            .claudeAllowsSampling("claude-sonnet-4-5", 16000))
        assertTrue(ModelConfiguration(supportsThinking = true, reasoningEffort = "none")
            .claudeAllowsSampling("claude-sonnet-4-5", 16000))
    }

    @Test fun aClaudeMaxOverrideRemainsValidAfterSwitchingProviders() {
        val model = ModelConfiguration(supportsThinking = true)
        assertEquals("max", model.withConversationReasoning("max", ProviderType.ANTHROPIC).reasoningEffort)
        assertEquals("xhigh", model.withConversationReasoning("max", ProviderType.OPENAI_CODEX).reasoningEffort)
        val gemini = model.withConversationReasoning("max", ProviderType.GOOGLE)
        assertEquals("high", gemini.googleThinkingConfig()!!["thinkingLevel"]!!.jsonPrimitive.content)
    }
}
