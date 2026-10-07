package org.starfall.multigateway

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.starfall.multigateway.ui.chat.reasoningEffortEnabled
import org.starfall.multigateway.ui.chat.reasoningEffortIndex
import org.starfall.multigateway.ui.chat.reasoningEffortOptions
import org.starfall.multigateway.data.model.ProviderType

class ReasoningEffortControlTest {
    @Test fun disabledValuesAreRecognized() {
        assertFalse(reasoningEffortEnabled("none"))
        assertFalse(reasoningEffortEnabled(" OFF "))
        assertTrue(reasoningEffortEnabled(null))
        assertTrue(reasoningEffortEnabled("medium"))
    }

    @Test fun sliderIndexMapsToKnownEffortAndDefaultsSafely() {
        assertEquals(0, reasoningEffortIndex("none"))
        assertEquals(0, reasoningEffortIndex("off"))
        assertEquals(1, reasoningEffortIndex(null))
        assertEquals(2, reasoningEffortIndex("LOW"))
        assertEquals(5, reasoningEffortIndex("xhigh"))
        assertEquals(1, reasoningEffortIndex("unknown"))
    }

    @Test fun claudeLabelsMatchTheBudgetsSentByTheApp() {
        for (provider in listOf(ProviderType.ANTHROPIC, ProviderType.CLAUDE_CODE)) {
            val options = reasoningEffortOptions(provider)
            assertEquals(listOf("Off", "Default", "1024", "2048", "4096", "8192"), options.map { it.label })
            assertEquals(listOf("none", null, "low", "medium", "high", "xhigh"), options.map { it.effort })
        }
        assertEquals("2999", reasoningEffortOptions(ProviderType.ANTHROPIC, maxTokens = 3000).last().label)
    }

    @Test fun geminiOmitsDuplicateXHighAndRecognizesSavedOverrides() {
        val options = reasoningEffortOptions(ProviderType.GOOGLE)
        assertEquals(listOf("Off", "Default", "Low", "Medium", "High"), options.map { it.label })
        assertEquals(4, reasoningEffortIndex("xhigh", options))
        assertEquals(4, reasoningEffortIndex(" HIGH ", options))
        assertEquals(0, reasoningEffortIndex("off", options))
    }

    @Test fun antigravityUsesTheUnderlyingModelFamily() {
        assertEquals(reasoningEffortOptions(ProviderType.GOOGLE),
            reasoningEffortOptions(ProviderType.ANTIGRAVITY, "gemini-3-pro"))
        assertEquals(reasoningEffortOptions(ProviderType.ANTHROPIC),
            reasoningEffortOptions(ProviderType.ANTIGRAVITY, "Claude-Sonnet"))
        assertEquals("X-High", reasoningEffortOptions(ProviderType.OPENAI_CODEX).last().label)
        assertEquals("Maximum", reasoningEffortOptions(ProviderType.OLLAMA).last().label)
    }

    @Test fun modernClaudeLabelsAndStoredMaximumOverridesMatchNativeEffort() {
        val options = reasoningEffortOptions(ProviderType.ANTHROPIC, "claude-opus-4-6")
        assertEquals(listOf("Off", "Default", "Low", "Medium", "High", "Max"), options.map { it.label })
        assertEquals(options.lastIndex, reasoningEffortIndex("xhigh", options))
        val opus47 = reasoningEffortOptions(ProviderType.CLAUDE_CODE, "claude-opus-4-7")
        assertEquals(listOf("none", null, "low", "medium", "high", "xhigh", "max"), opus47.map { it.effort })
        val legacy = reasoningEffortOptions(ProviderType.ANTHROPIC, "claude-sonnet-4-5")
        assertEquals(legacy.lastIndex, reasoningEffortIndex("max", legacy))
    }

    @Test fun alwaysOnClaudeDoesNotOfferAnInvalidOffSetting() {
        val options = reasoningEffortOptions(ProviderType.ANTHROPIC, "claude-opus-5-5")
        assertEquals("Default", options.first().label)
        assertEquals(0, reasoningEffortIndex(null, options))
        assertEquals(1, reasoningEffortIndex("none", options))
        val sonnet = reasoningEffortOptions(ProviderType.ANTHROPIC, "claude-sonnet-5-5")
        assertEquals("Between tools", sonnet.first().label)
    }
}
