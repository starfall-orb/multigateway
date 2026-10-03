package org.starfall.multigateway

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.starfall.multigateway.data.local.preferences.AppPreferences
import org.starfall.multigateway.ui.chat.ChatAppBar
import org.starfall.multigateway.ui.settings.SettingsScreen

class GeneralSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun showSettings(destinations: MutableList<String> = mutableListOf(), autoScroll: (Boolean) -> Unit = {}) {
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    appPreferences = AppPreferences(), conversationCount = 12, providerCount = 3,
                    onThemeChange = {}, onAmoledChange = {}, onDynamicColorChange = {}, onColorSchemeChange = {},
                    onContinueLastConversationChange = {}, onPersistChatSelectionChange = {}, onAutoScrollChange = autoScroll,
                    onEnableVibrationChange = {}, onHideStatusBarChange = {}, onDebugModeChange = {}, onLatexModeChange = {},
                    onClearAllConversations = {}, onResetAllData = {}, onBack = { destinations.add("Back") },
                    onNavigateToSystemTools = { destinations.add("Default Models") },
                    onNavigateToProviders = { destinations.add("Providers") }, onNavigateToMcp = { destinations.add("MCP Manage") },
                    onNavigateToSpeech = { destinations.add("Speech Services") }, onNavigateToStorage = { destinations.add("Storage") }
                )
            }
        }
    }

    private fun scrollListTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    @Test fun unifiedSettingsRoutesEveryConfigurationItem() {
        val destinations = mutableListOf<String>()
        showSettings(destinations)
        compose.onNodeWithText("General Settings").assertIsDisplayed()
        compose.onNodeWithText("Configuration").assertIsDisplayed()
        val expected = listOf("Default Models", "Providers", "MCP Manage", "Speech Services", "Storage")
        expected.forEach { scrollListTo(it); compose.onNodeWithText(it).performClick() }
        compose.onNodeWithText("System Settings").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(expected, destinations) }
    }

    @Test fun aboutContainsUpdatesAndBackReturnsToGeneralSettings() {
        val destinations = mutableListOf<String>()
        showSettings(destinations)
        compose.onNodeWithText("Software Update").assertDoesNotExist()
        scrollListTo("About")
        compose.onNodeWithText("About").performClick()
        compose.onNodeWithText("Software Update").assertIsDisplayed()
        compose.onNodeWithText("Check for updates").assertIsDisplayed()
        compose.onNodeWithText("Checking GitHub Releases...").assertDoesNotExist()
        compose.onNodeWithText("License").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("General Settings").assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<String>(), destinations) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(listOf("Back"), destinations) }
    }

    @Test fun preferenceRowTogglesOnce() {
        val updates = mutableListOf<Boolean>()
        showSettings(autoScroll = { updates.add(it) })
        scrollListTo("Preferences")
        compose.onNodeWithText("Preferences").performClick()
        compose.onNodeWithText("Auto scroll").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(true), updates) }
    }

    @Test fun chatToolbarOpensDrawerOnLeftAndSettingsOnRight() {
        var drawer = 0
        var settings = 0
        compose.setContent { MaterialTheme { ChatAppBar(null, { drawer++ }, { settings++ }) } }
        val left = compose.onNodeWithContentDescription("Open side navigation")
        val right = compose.onNodeWithContentDescription("General Settings")
        assertTrue(left.fetchSemanticsNode().boundsInRoot.left < right.fetchSemanticsNode().boundsInRoot.left)
        left.performClick()
        right.performClick()
        compose.runOnIdle { assertEquals(1, drawer); assertEquals(1, settings) }
    }
}
