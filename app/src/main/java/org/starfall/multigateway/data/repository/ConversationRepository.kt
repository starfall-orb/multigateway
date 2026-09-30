package org.starfall.multigateway.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import org.starfall.multigateway.data.local.db.AppDatabase
import org.starfall.multigateway.data.local.db.entities.*
import org.starfall.multigateway.data.model.*

class ConversationRepository(private val db: AppDatabase) {
    private val dao = db.conversationDao()

    val allConversations: Flow<List<Conversation>> = dao.getAllConversations().map { entities ->
        entities.map { entityToModel(it) }
    }

    suspend fun getById(id: String): Conversation? {
        val entity = dao.getConversationById(id) ?: return null
        return entityToModel(entity)
    }

    suspend fun saveConversation(conversation: Conversation) {
        dao.insertOrUpdate(modelToEntity(conversation))
    }

    suspend fun deleteConversation(id: String) {
        dao.deleteById(id)
    }

    suspend fun deleteConversations(ids: List<String>) {
        db.withTransaction { ids.chunked(500).forEach { dao.deleteByIds(it) } }
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }

    private fun entityToModel(entity: ConversationEntity): Conversation {
        val messages: List<StoredMessage> = try {
            json.decodeFromString(entity.messagesJson)
        } catch (e: Exception) {
            emptyList()
        }
        return Conversation(
            id = entity.id,
            title = entity.title,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            messages = messages.map { message -> message.copy(versions = message.versions.map { version ->
                version.copy(toolActivity = version.toolActivity.map { activity ->
                    if(activity.status == "running") activity.copy(status = "interrupted", summary = "The previous run did not finish.") else activity
                })
            }) },
            tokenCount = entity.tokenCount,
            providerId = entity.providerId,
            modelId = entity.modelId,
            profileId = entity.profileId,
            summary = entity.summaryJson?.let { raw ->
                runCatching { json.decodeFromString<ConversationSummary>(raw) }.getOrNull()
            },
            reasoningEffort = entity.reasoningEffort
        )
    }

    private fun modelToEntity(conversation: Conversation): ConversationEntity {
        return ConversationEntity(
            id = conversation.id,
            title = conversation.title,
            createdAt = conversation.createdAt,
            updatedAt = conversation.updatedAt,
            messagesJson = json.encodeToString(conversation.messages),
            tokenCount = conversation.tokenCount,
            providerId = conversation.providerId,
            modelId = conversation.modelId,
            profileId = conversation.profileId,
            summaryJson = conversation.summary?.let { json.encodeToString(it) },
            reasoningEffort = conversation.reasoningEffort
        )
    }
}

