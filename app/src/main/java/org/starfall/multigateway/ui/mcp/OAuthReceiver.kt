package org.starfall.multigateway.ui.mcp

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object OAuthReceiver {
    private val _tokenFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val tokenFlow = _tokenFlow.asSharedFlow()

    fun postToken(token: String) {
        _tokenFlow.tryEmit(token)
    }
}
