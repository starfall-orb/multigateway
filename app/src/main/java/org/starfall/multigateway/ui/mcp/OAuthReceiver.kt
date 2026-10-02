package org.starfall.multigateway.ui.mcp

import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object OAuthReceiver {
    private val pending = MutableStateFlow<String?>(null)
    private var expectedState: String? = null
    val tokenFlow = pending.asStateFlow()

    @Synchronized
    fun begin(): String {
        pending.value = null
        return UUID.randomUUID().toString().also { expectedState = it }
    }

    @Synchronized
    fun postToken(token: String, state: String?): Boolean {
        if (expectedState == null || state != expectedState || token.isBlank()) return false
        expectedState = null
        pending.value = token
        return true
    }

    @Synchronized
    fun cancel(state: String?): Boolean {
        if (expectedState == null || state != expectedState) return false
        expectedState = null
        pending.value = null
        return true
    }

    fun consume(token: String) { pending.compareAndSet(token, null) }
}
