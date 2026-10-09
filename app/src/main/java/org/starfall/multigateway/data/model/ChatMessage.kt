package org.starfall.multigateway.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ChatRole {
    @SerialName("user") USER,
    @SerialName("model") MODEL,
    @SerialName("system") SYSTEM
}

@Serializable
data class MessageVersion(
    val content: String = "",
    val timestamp: String = "",
    val files: List<String> = emptyList(),
    val toolActivity: List<ToolActivity> = emptyList(),
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("reasoning_signature") val reasoningSignature: String? = null,
    @SerialName("processing_finished_at") val processingFinishedAt: Long? = null,
    @SerialName("provider_id") val providerId: String = "",
    @SerialName("model_id") val modelId: String = "",
    @SerialName("model_display_name") val modelDisplayName: String = "",
    @SerialName("generation_interrupted") val generationInterrupted: Boolean = false
)

@Serializable
data class StoredMessage(
    val id: String,
    val role: ChatRole,
    val versions: List<MessageVersion> = emptyList(),
    @SerialName("active_version_index") val activeVersionIndex: Int = 0,
    @kotlinx.serialization.Transient val isQueued: Boolean = false
) {
    val activeVersion: MessageVersion
        get() {
            if (versions.isEmpty()) return MessageVersion()
            val idx = activeVersionIndex.coerceIn(0, versions.size - 1)
            return versions[idx]
        }

    val content: String
        get() = activeVersion.content

    val files: List<String>
        get() = activeVersion.files
    val reasoningSignature: String?
        get() = activeVersion.reasoningSignature


    val reasoningContent: String?
        get() = activeVersion.reasoningContent

}

@Serializable
enum class SummaryRole { SYSTEM, ASSISTANT, USER }

@Serializable
data class ConversationSummary(
    val id: String,
    val content: String,
    @SerialName("through_message_id") val throughMessageId: String,
    val role: SummaryRole = SummaryRole.SYSTEM,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis()
)

data class ConversationSummaryRequest(
    val targetTokens: Int,
    val chunked: Boolean = false,
    val tokensPerChunk: Int = 20_000
)

data class ConversationSummaryProgress(
    val conversationId: String,
    val throughMessageId: String,
    val label: String,
    val progress: Float
)

@Serializable
data class Conversation(
    val id: String,
    val title: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    val messages: List<StoredMessage> = emptyList(),
    @SerialName("token_count") val tokenCount: Int? = null,
    @SerialName("provider_id") val providerId: String = "",
    @SerialName("model_id") val modelId: String = "",
    @SerialName("profile_id") val profileId: String? = null,
    val summary: ConversationSummary? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null
)
