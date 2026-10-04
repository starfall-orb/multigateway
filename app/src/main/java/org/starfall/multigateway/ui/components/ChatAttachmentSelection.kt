package org.starfall.multigateway.ui.components

import androidx.compose.runtime.*

@Stable
class ChatAttachmentSelection {
    val attachments = mutableStateOf<List<String>>(emptyList())
    var requestId by mutableIntStateOf(0)
        private set

    fun toggle(reference: String) {
        attachments.value = if (reference in attachments.value) attachments.value - reference
            else (attachments.value + reference).distinct()
        requestId++
    }

    fun clear() { attachments.value = emptyList() }
}

val LocalChatAttachmentSelection = compositionLocalOf<ChatAttachmentSelection?> { null }
