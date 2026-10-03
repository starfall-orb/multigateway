package org.starfall.multigateway.data.local.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messagesJson: String,
    val tokenCount: Int?,
    val providerId: String,
    val modelId: String,
    val profileId: String?,
    val summaryJson: String?,
    val reasoningEffort: String?
)

@Entity(tableName = "chat_profiles")
data class ChatProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val icon: String?,
    val configJson: String,
    val activeMcpJson: String,
    val activeModelToolsJson: String,
    val sortOrder: Int
)

@Entity(tableName = "provider_groups")
data class ProviderGroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int
)

@Entity(tableName = "llm_providers")
data class LlmProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    val baseUrl: String,
    val authJson: String,
    val configJson: String,
    val icon: String?,
    val sortOrder: Int,
    val groupId: String?
)

@Entity(tableName = "llm_models")
data class LlmModelsEntity(
    @PrimaryKey val id: String,
    val modelsJson: String
)

@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: String,
    val url: String?,
    val headersJson: String?,
    val cachedToolsJson: String?,
    val sortOrder: Int
)

@Entity(tableName = "speech_services")
data class SpeechServiceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val provider: String,
    val modelId: String?,
    val voice: String,
    val speed: Float,
    val pitch: Float,
    val apiKey: String,
    val sortOrder: Int
)
