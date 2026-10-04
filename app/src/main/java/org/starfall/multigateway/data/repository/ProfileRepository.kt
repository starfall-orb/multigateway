package org.starfall.multigateway.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import org.starfall.multigateway.data.local.db.AppDatabase
import org.starfall.multigateway.data.local.db.SecretCipher
import org.starfall.multigateway.data.local.db.entities.*
import org.starfall.multigateway.data.model.*

class ProfileRepository(private val db: AppDatabase) {
    private val dao = db.chatProfileDao()

    private val stored: Flow<List<ChatProfile>> = dao.getAllProfiles().map { entities ->
        entities.map { entityToModel(it) }
    }

    private val state = ImmediateState(stored)
    val allProfiles: Flow<List<ChatProfile>> = state.flow

    suspend fun getById(id: String): ChatProfile? {
        state.value?.let { items -> return items.firstOrNull { it.id == id } }
        return withContext(Dispatchers.IO) { dao.getProfileById(id)?.let(::entityToModel) }
    }

    suspend fun saveProfile(profile: ChatProfile) {
        state.mutate({ it.upsert(profile) { item -> item.id }.sortedBy { it.sortOrder } }) {
            dao.insertOrUpdate(modelToEntity(profile))
        }
    }

    suspend fun deleteProfile(id: String) {
        state.mutate({ items -> items.filterNot { it.id == id } }) {
            dao.deleteById(id)
        }
    }

    suspend fun reorderProfiles(ids: List<String>) {
        state.mutate({ items -> items.map { item -> ids.indexOf(item.id).takeIf { it >= 0 }?.let { item.copy(sortOrder = it) } ?: item }.sortedBy { it.sortOrder } }) {
            db.withTransaction {
                ids.forEachIndexed { index, id -> dao.updateSortOrder(id, index) }
            }
        }
    }

    private fun entityToModel(entity: ChatProfileEntity): ChatProfile {
        val config: LlmChatConfig = try {
            json.decodeFromString(entity.configJson)
        } catch (e: Exception) {
            LlmChatConfig()
        }
        return ChatProfile(
            id = entity.id,
            name = entity.name,
            icon = entity.icon,
            config = config,
            sortOrder = entity.sortOrder
        )
    }

    private fun modelToEntity(profile: ChatProfile): ChatProfileEntity {
        return ChatProfileEntity(
            id = profile.id,
            name = profile.name,
            icon = profile.icon,
            configJson = json.encodeToString(profile.config),
            activeMcpJson = "[]",
            activeModelToolsJson = "[]",
            sortOrder = profile.sortOrder
        )
    }
}

