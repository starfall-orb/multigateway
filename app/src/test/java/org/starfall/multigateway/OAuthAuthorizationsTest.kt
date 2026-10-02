package org.starfall.multigateway

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.configuration.OAuthAuthorizations

class OAuthAuthorizationsTest {
    private val provider = LlmProviderInfo("first-login", "Account", ProviderType.ANTIGRAVITY, baseUrl = ProviderType.ANTIGRAVITY.defaultBaseUrl)

    @Test fun screenCancellationDoesNotLoseAuthorizationAndRetryJoinsTheSameRequest() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val release = CompletableDeferred<Unit>()
        var attempts = 0
        var saved = false
        val coordinator = OAuthAuthorizations(owner) {
            attempts++
            release.await()
            saved = true
            Result.success(it.copy(auth = Authorization(AuthMethod.OAUTH, value = "saved")))
        }
        try {
            val firstScreen = async { coordinator.authorize(provider) }
            yield()
            firstScreen.cancelAndJoin()
            val returnedScreen = async { coordinator.authorize(provider) }
            yield()
            assertEquals(1, attempts)
            release.complete(Unit)
            assertEquals("saved", returnedScreen.await().getOrThrow().auth.value)
            assertTrue(saved)
        } finally { owner.cancel() }
    }

    @Test fun completedOrFailedAuthorizationDoesNotBlockAnExplicitNewAttempt() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var attempts = 0
        val coordinator = OAuthAuthorizations(owner) {
            if (++attempts == 1) Result.failure(IllegalStateException("First attempt failed"))
            else Result.success(it)
        }
        try {
            assertTrue(coordinator.authorize(provider).isFailure)
            assertTrue(coordinator.authorize(provider).isSuccess)
            assertEquals(2, attempts)
        } finally { owner.cancel() }
    }
}
