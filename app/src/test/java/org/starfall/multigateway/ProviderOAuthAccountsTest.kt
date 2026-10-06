package org.starfall.multigateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.multigateway.data.adapter.common.*
import org.starfall.multigateway.data.local.db.AppDatabase
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.repository.LlmRepository
import org.starfall.multigateway.data.service.LlmService

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ProviderOAuthAccountsTest {
    private val marker = "account:antigravity:v1"
    private val legacy = LlmProviderInfo("provider", "Antigravity", ProviderType.ANTIGRAVITY,
        baseUrl = ProviderType.ANTIGRAVITY.defaultBaseUrl,
        auth = Authorization(AuthMethod.OAUTH, "first@example.test", marker))

    @Test fun legacyAccountIsImportedAndAccountsRoundTripWithoutTokens() {
        assertEquals("provider", legacy.oauthCredentialId)
        val imported = legacy.recordOAuthAccount("Primary")
        assertEquals("provider", imported.config.oauthAccounts.single().id)
        val second = imported.copy(auth = Authorization(AuthMethod.OAUTH, "second@example.test", marker, "provider:second"))
            .recordOAuthAccount("Backup")
        assertEquals(listOf("Primary", "Backup"), second.config.oauthAccounts.map { it.label })
        assertEquals("provider:second", second.oauthCredentialId)
        val json = Json.encodeToString(second)
        assertFalse(json.contains("accessToken")); assertFalse(json.contains("refreshToken"))
        assertEquals(second, Json.decodeFromString<LlmProviderInfo>(json))
        assertEquals(second, second.recordOAuthAccount())
    }

    @Test fun removingSelectedAccountSelectsRemainingAccountAndRemovingOtherKeepsSelection() {
        val first = legacy.recordOAuthAccount()
        val second = first.copy(auth = Authorization(AuthMethod.OAUTH, "second@example.test", marker, "provider:second"))
            .recordOAuthAccount()
        assertEquals(second.auth, second.removeOAuthAccount("provider").auth)
        val remaining = second.removeOAuthAccount("provider:second")
        assertEquals("first@example.test", remaining.auth.key)
        assertEquals("provider", remaining.oauthCredentialId)
        val empty = remaining.removeOAuthAccount("provider")
        assertEquals(AuthMethod.OAUTH, empty.auth.method)
        assertNull(empty.auth.value)
        assertTrue(empty.config.oauthAccounts.isEmpty())
    }

    @Test fun repositoryDeletesOnlyRequestedAccountThenProviderDeletionClearsAllSlots() = runBlocking {
        installTestAndroidKeyStore()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val repository = LlmRepository(db, LlmService(context))
            val store = AccountTokenStore(context, "antigravity")
            val first = legacy.recordOAuthAccount()
            val second = first.copy(auth = Authorization(AuthMethod.OAUTH, "second@example.test", marker, "provider:second"))
                .recordOAuthAccount()
            store.save("provider", AccountTokenState("first-secret", "first-refresh"))
            store.save("provider:second", AccountTokenState("second-secret", "second-refresh"))
            repository.saveProvider(second)
            // Removing an unselected account must not sign out the selected account in storage.
            repository.clearOAuthCredentials(second.copy(auth = first.auth)).getOrThrow()
            assertNull(store.load("provider"))
            assertEquals("second-secret", store.load("provider:second")!!.accessToken)
            val persisted = repository.getProviderById("provider")!!
            assertEquals("provider:second", persisted.oauthCredentialId)
            assertEquals(listOf("provider:second"), persisted.config.oauthAccounts.map { it.id })
            repository.deleteProvider("provider")
            assertNull(store.load("provider:second"))
        } finally { db.close() }
    }
}
