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


    @Test fun testAllRunsStrictlySequentiallyAndBlocksManualTestsDuringBatch() = runBlocking {
        val firstGate = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        var active = 0
        var maximum = 0
        val tests = ModelConnectionTests(CoroutineScope(coroutineContext + Dispatchers.Unconfined)) { _, id ->
            calls += id
            active++
            maximum = maxOf(maximum, active)
            try {
                if (id == "model-0") {
                    firstStarted.complete(Unit)
                    firstGate.await()
                }
                Result.success(id)
            } finally {
                active--
            }
        }

        tests.testAll(provider, listOf("model-0", "model-1", "model-2"))
        firstStarted.await()
        assertTrue(tests.batchRunning.value)
        assertEquals(listOf("model-0"), calls)
        assertEquals(1, active)

        tests.test(provider, "manual")
        assertFalse(tests.running["manual"] == true)
        assertFalse("manual" in calls)

        firstGate.complete(Unit)
        withTimeout(1_000) {
            while (tests.batchRunning.value) yield()
        }

        assertEquals(listOf("model-0", "model-1", "model-2"), calls)
        assertEquals(1, maximum)
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
