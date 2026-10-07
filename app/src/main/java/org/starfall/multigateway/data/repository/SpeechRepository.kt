package org.starfall.multigateway.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.starfall.multigateway.data.local.db.AppDatabase
import org.starfall.multigateway.data.local.db.SecretCipher
import org.starfall.multigateway.data.local.db.entities.*
import org.starfall.multigateway.data.model.*

class SpeechRepository(private val db: AppDatabase) {
    private val dao = db.speechServiceDao()

    private val stored: Flow<List<SpeechService>> = dao.getAllSpeechServices().map { entities ->
        entities.map { entityToModel(it) }
    }

    private val state = ImmediateState(stored)
    val allServices: Flow<List<SpeechService>> = state.flow

    suspend fun getById(id: String): SpeechService? {
        state.value?.let { items -> return items.firstOrNull { it.id == id } }
        return withContext(Dispatchers.IO) { dao.getServiceById(id)?.let(::entityToModel) }
    }

    suspend fun saveService(service: SpeechService) {
        state.mutate({ it.upsert(service) { item -> item.id }.sortedBy { it.sortOrder } }) {
            dao.insertOrUpdate(modelToEntity(service))
        }
    }

    suspend fun deleteService(id: String) {
        state.mutate({ items -> items.filterNot { it.id == id } }) {
            dao.deleteById(id)
        }
    }

    suspend fun reorderServices(ids: List<String>) {
        state.mutate({ items -> items.map { item -> ids.indexOf(item.id).takeIf { it >= 0 }?.let { item.copy(sortOrder = it) } ?: item }.sortedBy { it.sortOrder } }) {
            db.withTransaction {
                ids.forEachIndexed { index, id -> dao.updateSortOrder(id, index) }
            }
        }
    }

    private fun entityToModel(entity: SpeechServiceEntity): SpeechService {
        val options = entity.optionsJson?.let { Json.parseToJsonElement(SecretCipher.decrypt(it)).jsonObject }
        return SpeechService(
            id = entity.id,
            name = entity.name,
            icon = options?.get("icon")?.jsonPrimitive?.contentOrNull,
            provider = entity.provider,
            modelId = entity.modelId,
            voice = entity.voice,
            speed = entity.speed,
            pitch = entity.pitch,
            apiKey = SecretCipher.decrypt(entity.apiKey),
            sortOrder = entity.sortOrder,
            instructions = options?.get("instructions")?.jsonPrimitive?.content.orEmpty(),
            responseFormat = options?.get("responseFormat")?.jsonPrimitive?.content ?: "mp3",
            languageCode = options?.get("languageCode")?.jsonPrimitive?.content.orEmpty(),
            extraBody = options?.get("extraBody")?.jsonObject ?: JsonObject(emptyMap())
        )
    }

    private fun modelToEntity(service: SpeechService): SpeechServiceEntity {
        return SpeechServiceEntity(
            id = service.id,
            name = service.name,
            provider = service.provider,
            modelId = service.modelId,
            voice = service.voice,
            speed = service.speed,
            pitch = service.pitch,
            apiKey = SecretCipher.encrypt(service.apiKey),
            sortOrder = service.sortOrder,
            optionsJson = SecretCipher.encrypt(buildJsonObject {
                service.icon?.let { put("icon", it) }
                put("instructions", service.instructions)
                put("responseFormat", service.responseFormat)
                put("languageCode", service.languageCode)
                put("extraBody", service.extraBody)
            }.toString())
        )
    }
}
