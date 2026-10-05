package org.starfall.multigateway

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.providers.ModelConnectionRemovals
import org.starfall.multigateway.ui.providers.ModelConnectionTests

class ModelConnectionRemovalsTest {
    private val provider = LlmProviderInfo("p", "Provider", ProviderType.OPENAI, baseUrl = "https://example.test/v1")

    @Test fun removeAndRestoreWhileTestingKeepRowsAndOnlyCommitOnClose() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val tests = ModelConnectionTests(CoroutineScope(coroutineContext + Dispatchers.Unconfined)) { _, _ ->
            gate.await(); Result.failure(IllegalStateException("Unavailable"))
        }
        val removals = ModelConnectionRemovals(listOf("a", "b"))
        var removed = emptySet<String>()
        var closes = 0
        tests.test(provider, "a")
        assertTrue(tests.running["a"] == true)
        removals.toggle("a")
        assertEquals(listOf("a"), removals.pending.toList())
        assertEquals(listOf("a", "b"), removals.modelIds)
        assertTrue(removed.isEmpty())
        removals.toggle("a")
        assertTrue(removals.pending.isEmpty())
        removals.toggle("a")
        removals.close({ removed = it; tests.forget(it) }, { closes++ })
        assertEquals(setOf("a"), removed)
        assertEquals(1, closes)
        gate.complete(Unit)
        yield()
        assertFalse(tests.results.containsKey("a"))
        assertFalse(tests.running.containsKey("a"))
        removals.close({ fail("Removal must not be committed twice") }, { closes++ })
        assertEquals(1, closes)
    }

    @Test fun bulkRemovalCanMarkFailuresDuringBatchAndRestoredRowsAreRetained() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val tests = ModelConnectionTests(CoroutineScope(coroutineContext + Dispatchers.Unconfined)) { _, id ->
            if (id == "a") Result.failure(IllegalStateException("Unavailable"))
            else { gate.await(); Result.success("OK") }
        }
        val removals = ModelConnectionRemovals(listOf("a", "b"))
        tests.testAll(provider, removals.modelIds)
        assertTrue(tests.batchRunning.value)
        assertTrue(tests.running["b"] == true)
        removals.mark(removals.modelIds.filter { tests.results[it]?.isFailure == true })
        removals.mark(listOf("a"))
        assertEquals(listOf("a"), removals.pending.toList())
        assertEquals(listOf("a", "b"), removals.modelIds)
        removals.restoreAll()
        removals.close({ fail("Restored models must not be deleted") }, {})
        gate.complete(Unit)
        yield()
        assertTrue(tests.results["a"]!!.isFailure)
        assertTrue(tests.results["b"]!!.isSuccess)
    }

    @Test fun closingDuringBatchPreventsForgottenActiveAndQueuedModelsFromReturning() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val tests = ModelConnectionTests(CoroutineScope(coroutineContext + Dispatchers.Unconfined)) { _, id ->
            calls += id
            if (id == "a") gate.await()
            Result.success("OK")
        }
        val removals = ModelConnectionRemovals(listOf("a", "b", "c"))
        tests.testAll(provider, removals.modelIds)
        removals.mark(listOf("a", "b"))
        var removed = emptySet<String>()
        removals.close({ removed = it; tests.forget(it) }, {})
        gate.complete(Unit)
        yield()
        assertEquals(setOf("a", "b"), removed)
        assertEquals(listOf("a", "c"), calls)
        assertFalse(tests.results.containsKey("a"))
        assertFalse(tests.running.containsKey("a"))
        assertFalse(tests.results.containsKey("b"))
        assertTrue(tests.results["c"]!!.isSuccess)
        assertFalse(tests.batchRunning.value)
    }

    @Test fun lateTestCannotOverwriteResultAfterModelIsReaddedAndRetested() = runBlocking {
        val oldGate = CompletableDeferred<Unit>()
        val newGate = CompletableDeferred<Unit>()
        var calls = 0
        val tests = ModelConnectionTests(CoroutineScope(coroutineContext + Dispatchers.Unconfined)) { _, _ ->
            if (++calls == 1) { oldGate.await(); Result.failure(IllegalStateException("Old failure")) }
            else { newGate.await(); Result.success("New success") }
        }
        tests.test(provider, "a")
        tests.forget(listOf("a"))
        tests.test(provider, "a")
        oldGate.complete(Unit)
        yield()
        assertTrue(tests.running["a"] == true)
        assertFalse(tests.results.containsKey("a"))
        newGate.complete(Unit)
        yield()
        assertEquals("New success", tests.results["a"]!!.getOrNull())
        assertFalse(tests.running["a"] == true)
    }
}
