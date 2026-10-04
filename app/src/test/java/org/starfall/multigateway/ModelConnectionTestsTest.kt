package org.starfall.multigateway

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.providers.ModelConnectionTests

class ModelConnectionTestsTest {
    private val provider = LlmProviderInfo("p", "Provider", ProviderType.OPENAI, baseUrl = "https://example.test/v1")

    @Test fun testsAtMostFourModelsInParallelAndDeduplicatesPendingRequests() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        var calls = 0
        val tests = ModelConnectionTests(CoroutineScope(coroutineContext + Dispatchers.Unconfined)) { _, id ->
            calls++
            active++
            maximum = maxOf(maximum, active)
            try { gate.await(); Result.success(id) } finally { active-- }
        }
        repeat(6) { tests.test(provider, "model-$it") }
        tests.test(provider, "model-0")
        assertEquals(4, active)
        gate.complete(Unit)
        yield()
        assertEquals(6, calls)
        assertEquals(4, maximum)
        assertEquals(6, tests.results.size)
        assertFalse(tests.running.values.any { it })
    }

    @Test fun cancellationClearsRunningStateAndDoesNotBecomeAConnectionFailure() = runBlocking {
        val job = SupervisorJob()
        val tests = ModelConnectionTests(CoroutineScope(job + Dispatchers.Unconfined)) { _, _ ->
            awaitCancellation()
        }
        tests.test(provider, "pending")
        assertTrue(tests.running["pending"] == true)
        job.cancel()
        yield()
        assertFalse(tests.running["pending"] == true)
        assertFalse(tests.results.containsKey("pending"))
        tests.test(provider, "after-cancellation")
        assertFalse(tests.running.containsKey("after-cancellation"))
    }
}
