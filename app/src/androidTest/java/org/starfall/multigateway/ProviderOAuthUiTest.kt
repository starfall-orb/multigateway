package org.starfall.multigateway

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.providers.*

class ProviderOAuthUiTest {
    @get:Rule val compose = createComposeRule()
    private val accountTypes = listOf(ProviderType.OPENAI_CODEX, ProviderType.CLAUDE_CODE,
        ProviderType.GITHUB_COPILOT, ProviderType.ANTIGRAVITY)

    @Test fun accountProviderCardsShowSignInStatusInsteadOfUrlInBothLayouts() {
        var type by mutableStateOf(accountTypes.first())
        var grid by mutableStateOf(false)
        var signedIn by mutableStateOf(false)
        compose.setContent { MaterialTheme {
            ProviderUnifiedCard(LlmProviderInfo("p", "Provider", type, baseUrl = "https://hidden.example/v1",
                auth = Authorization(AuthMethod.OAUTH, value = if (signedIn) "marker" else null)), grid,
                onEdit = {}, onMoveToGroup = {}, onDelete = {})
        } }
        for (nextType in accountTypes) for (layout in listOf(false, true)) {
            compose.runOnIdle { type = nextType; grid = layout; signedIn = false }
            compose.onNodeWithText("https://hidden.example/v1").assertDoesNotExist()
            compose.onNodeWithText("Not signed in").assertIsDisplayed()
            compose.runOnIdle { signedIn = true }
            compose.onNodeWithText("Signed in").assertIsDisplayed()
        }
        compose.runOnIdle { type = ProviderType.OPENAI; signedIn = false }
        compose.onNodeWithText("https://hidden.example/v1").assertIsDisplayed()
    }

    @Test fun accountProviderEditorsHideBaseUrlAndAllowMultipleAccounts() {
        var type by mutableStateOf(accountTypes.first())
        compose.setContent { MaterialTheme { key(type) {
            ProviderEditScreen(LlmProviderInfo("p", "Provider", type, baseUrl = type.defaultBaseUrl,
                auth = type.defaultAuthorization()), isNew = false, onSaveModels = { _, _ -> },
                onReorderModels = { _, _ -> }, onDismiss = {}, onSave = {})
        } } }
        for (nextType in accountTypes) {
            compose.runOnIdle { type = nextType }
            compose.onNode(hasSetTextAction() and hasText("Base URL")).assertDoesNotExist()
            compose.onNodeWithText("Multiple Accounts").performScrollTo().performClick()
            compose.onNodeWithText("Manage Accounts").performScrollTo().performClick()
            compose.onNodeWithText("No signed-in accounts yet.").assertIsDisplayed()
            compose.onNodeWithContentDescription("Close").performClick()
        }
    }

    @Test fun accountManagerMarksSelectedAccountFirstAndSelectionClosesDialog() {
        val accounts = listOf(ProviderOAuthAccount("first", "Primary", "first@example.test", "marker"),
            ProviderOAuthAccount("second", "Backup", "second@example.test", "marker"))
        var selected: ProviderOAuthAccount? = null
        var open by mutableStateOf(true)
        compose.setContent { MaterialTheme { if (open) {
            ProviderOAuthAccountsDialog(accounts, "second", false, false, onSelect = { selected = it; open = false },
                onSaveLabel = { _, _ -> }, onAuthorize = { _, _ -> error("unused") },
                onCancelAuthorization = {}, onDelete = { error("unused") }, onDismiss = { open = false })
        } } }
        compose.onNodeWithText("Selected account").assertIsDisplayed()
        assertTrue(compose.onNodeWithText("Backup").fetchSemanticsNode().boundsInRoot.top <
            compose.onNodeWithText("Primary").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText("Primary").performClick()
        compose.runOnIdle { assertEquals("first", selected!!.id) }
        compose.onNodeWithText("Manage Accounts").assertDoesNotExist()
    }
}
