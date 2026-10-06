package org.starfall.multigateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.multigateway.data.adapter.antigravity.AntigravityAdapter
import org.starfall.multigateway.data.adapter.codex.CodexTokenStore
import org.starfall.multigateway.data.adapter.codex.CodexTokenState
import org.starfall.multigateway.data.adapter.codex.OpenAICodexAdapter
import org.starfall.multigateway.data.adapter.common.*
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.AttachmentResolver

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class OAuthPersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun prepareKeystore() = installTestAndroidKeyStore()

    @Test fun antigravityAuthorizationAndConnectionDoNotRequireCodeAssistProject() = runBlocking {
        val id = "without-code-assist"
        AccountTokenStore(context, "antigravity").save(id, AccountTokenState("access", "refresh"))
        val provider = LlmProviderInfo(id, "Antigravity", ProviderType.ANTIGRAVITY,
            baseUrl = "http://127.0.0.1:1",
            auth = Authorization(AuthMethod.OAUTH, value = "account:antigravity:v1"))
        val adapter = AntigravityAdapter(context, AttachmentResolver(context))
        assertTrue(adapter.testConnection(provider).isSuccess)
        assertEquals("Bearer access", adapter.prepareAuthenticatedProvider(provider).auth.token)
        assertNull(AccountTokenStore(context, "antigravity").load(id)!!.projectId)
    }

    @Test fun firstCodexCredentialSaveIsReadableByANewStore() {
        val token = CodexTokenState("access", "refresh", email = "test@example.com")
        CodexTokenStore(context).save("first-codex-login", token)
        assertEquals(token, CodexTokenStore(context).load("first-codex-login"))
    }

    @Test fun selectedAccountUsesItsOwnTokenAndClearingItPreservesOtherAccounts() = runBlocking {
        val store = AccountTokenStore(context, "antigravity")
        store.save("multi", AccountTokenState("legacy-access", "legacy-refresh"))
        store.save("multi:second", AccountTokenState("second-access", "second-refresh"))
        val provider = LlmProviderInfo("multi", "Antigravity", ProviderType.ANTIGRAVITY,
            baseUrl = "http://127.0.0.1:1", auth = Authorization(AuthMethod.OAUTH, value = "account:antigravity:v1"))
        val adapter = AntigravityAdapter(context, AttachmentResolver(context))
        assertEquals("Bearer legacy-access", adapter.prepareAuthenticatedProvider(provider).auth.token)
        val second = provider.copy(auth = provider.auth.copy(oauthAccountId = "multi:second"))
        assertEquals("Bearer second-access", adapter.prepareAuthenticatedProvider(second).auth.token)
        adapter.clearCredentials(second.oauthCredentialId)
        assertNull(store.load("multi:second"))
        assertEquals("Bearer legacy-access", adapter.prepareAuthenticatedProvider(provider).auth.token)
    }

    @Test fun codexCatalogLoadsSelectedAccountWithoutTouchingLegacyCredentials() = runBlocking {
        val store = CodexTokenStore(context)
        store.save("codex:second", CodexTokenState("second-access", "second-refresh"))
        val provider = LlmProviderInfo("codex", "Codex", ProviderType.OPENAI_CODEX, baseUrl = "",
            auth = Authorization(AuthMethod.OAUTH, value = "openai_codex_oauth", oauthAccountId = "codex:second"))
        val adapter = OpenAICodexAdapter(context, AttachmentResolver(context))
        assertTrue(adapter.fetchModels(provider).isNotEmpty())
        assertNull(store.load("codex"))
        adapter.clearCredentials(provider.oauthCredentialId)
        assertNull(store.load("codex:second"))
    }


}
