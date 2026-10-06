package org.starfall.multigateway.ui.drawer

import org.starfall.multigateway.data.model.ChatRole
import org.starfall.multigateway.data.model.Conversation
import org.starfall.multigateway.data.model.LlmProviderInfo

internal data class ConversationModelIcon(val image: String?, val name: String)

/** Use the last displayed model reply, including its selected regenerated version. */
internal fun Conversation.lastModelIcon(providers: Map<String, LlmProviderInfo>): ConversationModelIcon? {
    val version = messages.lastOrNull { it.role == ChatRole.MODEL && it.activeVersion.modelId.isNotBlank() }?.activeVersion
    val id = version?.modelId ?: modelId.takeIf { it.isNotBlank() } ?: return null
    val provider = providers[version?.providerId?.takeIf { it.isNotBlank() } ?: providerId]
    val config = provider?.config?.modelConfigs?.get(id)
    val name = config?.displayName?.takeIf { it.isNotBlank() }
        ?: version?.modelDisplayName?.takeIf { it.isNotBlank() }
        ?: id
    return ConversationModelIcon(config?.icon, name)
}
