package org.starfall.multigateway.data.model

import kotlinx.serialization.Serializable

@Serializable
data class SavedPrompt(val id: String, val name: String, val content: String, val role: ChatRole = ChatRole.SYSTEM)

@Serializable
data class PromptLibrary(val prompts: List<SavedPrompt> = emptyList(), val selectedIds: Set<String> = emptySet()) {
    val activePrompts: List<SavedPrompt> get() = prompts.filter { it.id in selectedIds }
    fun systemPrompt(): String = activePrompts.filter { it.role == ChatRole.SYSTEM }.joinToString("\n\n") { it.content }
    fun roleMessages(): List<StoredMessage> = activePrompts.filter { it.role != ChatRole.SYSTEM }.map {
        StoredMessage("prompt_${it.id}", it.role, listOf(MessageVersion(content = it.content)))
    }
    companion object {
        fun fromLegacy(prompt: String): PromptLibrary = if (prompt.isBlank()) PromptLibrary() else PromptLibrary(
            listOf(SavedPrompt("legacy-system-prompt", "Default system prompt", prompt)), setOf("legacy-system-prompt"))
    }
}
