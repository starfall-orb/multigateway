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
import org.starfall.multigateway.data.adapter.common.*
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.AttachmentResolver

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class OAuthPersistenceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun prepareKeystore() = installTestAndroidKeyStore()

    @Test fun antigravityCatalogAndConnectionDoNotRequireCodeAssistProject() = runBlocking {
        val id = "without-code-assist"
        AccountTokenStore(context, "antigravity").save(id, AccountTokenState("access", "refresh"))
        val provider = LlmProviderInfo(id, "Antigravity", ProviderType.ANTIGRAVITY,
            baseUrl = "http://127.0.0.1:1",
            auth = Authorization(AuthMethod.OAUTH, value = "account:antigravity:v1"))
        val adapter = AntigravityAdapter(context, AttachmentResolver(context))
        assertEquals(listOf("gemini-3-flash", "gemini-3.1-pro"), adapter.fetchModels(provider))
        assertTrue(adapter.testConnection(provider).isSuccess)
        assertEquals("Bearer access", adapter.prepareAuthenticatedProvider(provider).auth.token)
        assertNull(AccountTokenStore(context, "antigravity").load(id)!!.projectId)
    }

    @Test fun firstCodexCredentialSaveIsReadableByANewStore() {
        val token = CodexTokenState("access", "refresh", email = "test@example.com")
        CodexTokenStore(context).save("first-codex-login", token)
        assertEquals(token, CodexTokenStore(context).load("first-codex-login"))
    }


}
