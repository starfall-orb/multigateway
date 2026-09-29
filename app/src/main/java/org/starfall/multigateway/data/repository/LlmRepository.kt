package org.starfall.multigateway.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import org.starfall.multigateway.data.local.db.AppDatabase
import org.starfall.multigateway.data.local.db.SecretCipher
import org.starfall.multigateway.data.local.db.entities.*
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.LlmService

class LlmRepository(private val db: AppDatabase, private val service: LlmService) {
    suspend fun testConnection(provider: LlmProviderInfo) = service.testConnection(provider)
    suspend fun testModel(provider: LlmProviderInfo, modelId: String) = service.testModel(provider, modelId)
    suspend fun fetchProviderModels(provider: LlmProviderInfo) = service.fetchProviderModels(provider)
    suspend fun fetchOllamaModels(baseUrl: String) = service.fetchOllamaModels(baseUrl)

    private val providerDao = db.llmProviderDao()
    private val modelsDao = db.llmModelsDao()

    val allProviders: Flow<List<LlmProviderInfo>> = providerDao.getAllProviders().map { entities ->
        entities.map { providerEntityToModel(it) }
    }

    suspend fun getProviderById(id: String): LlmProviderInfo? {
        val entity = providerDao.getProviderById(id) ?: return null
        return providerEntityToModel(entity)
    }

    suspend fun authorizeProvider(provider: LlmProviderInfo): Result<LlmProviderInfo> = runCatching {
        val authorized = service.authorizeProvider(provider).getOrThrow()
        providerDao.getProviderById(provider.id)?.let(::providerEntityToModel)?.let { persisted ->
            providerDao.insertOrUpdate(
                providerModelToEntity(
                    persisted.copy(
                        type = authorized.type,
                        baseUrl = authorized.baseUrl,
                        auth = authorized.auth
                    )
                )
            )
        }
        authorized
    }

    suspend fun clearOAuthCredentials(provider: LlmProviderInfo): Result<LlmProviderInfo> = runCatching {
        service.clearAccountCredentials(provider.type, provider.id)
        val clearedAuth = Authorization(method = AuthMethod.OAUTH)
        providerDao.getProviderById(provider.id)?.let(::providerEntityToModel)?.let { persisted ->
            providerDao.insertOrUpdate(providerModelToEntity(persisted.copy(auth = clearedAuth)))
        }
        provider.copy(auth = clearedAuth)
    }

    suspend fun saveProvider(provider: LlmProviderInfo) {
        val previous = providerDao.getProviderById(provider.id)?.let(::providerEntityToModel)
        if (
            previous != null &&
            (previous.type != provider.type ||
                (previous.auth.method == AuthMethod.OAUTH && provider.auth.method != AuthMethod.OAUTH))
        ) {
            service.clearAccountCredentials(previous.type, previous.id)
        }
        providerDao.insertOrUpdate(providerModelToEntity(provider))
    }

    suspend fun deleteProvider(id: String) {
        providerDao.getProviderById(id)?.let(::providerEntityToModel)?.let {
            service.clearAccountCredentials(it.type, it.id)
        }
        providerDao.deleteById(id)
    }

    suspend fun reorderProviders(ids: List<String>) {
        ids.forEachIndexed { index, id -> providerDao.updateSortOrder(id, index) }
    }

    suspend fun getModelsForProvider(providerId: String): LlmProviderModels? {
        val entity = modelsDao.getModelsForProvider(providerId) ?: return null
        val models: List<LlmModel> = try {
            json.decodeFromString(entity.modelsJson)
        } catch (e: Exception) {
            emptyList()
        }
        return LlmProviderModels(id = entity.id, models = models)
    }

    suspend fun saveModelsForProvider(providerModels: LlmProviderModels) {
        modelsDao.insertOrUpdate(
            LlmModelsEntity(
                id = providerModels.id,
                modelsJson = json.encodeToString(providerModels.models)
            )
        )
    }

    private fun providerEntityToModel(entity: LlmProviderEntity): LlmProviderInfo {
        val type = try {
            ProviderType.valueOf(entity.type)
        } catch (e: Exception) {
            ProviderType.OPENAI
        }
        val authJson = SecretCipher.decrypt(entity.authJson)
        val configJson = SecretCipher.decrypt(entity.configJson)
        val auth: Authorization = try {
            json.decodeFromString(authJson)
        } catch (e: Exception) {
            Authorization()
        }
        val config: ProviderConfiguration = try {
            json.decodeFromString(configJson)
        } catch (e: Exception) {
            ProviderConfiguration()
        }
        return LlmProviderInfo(
            id = entity.id,
            name = entity.name,
            type = type,
            auth = auth,
            icon = entity.icon,
            baseUrl = SecretCipher.decrypt(entity.baseUrl),
            config = config,
            sortOrder = entity.sortOrder
        )
    }

    private fun providerModelToEntity(provider: LlmProviderInfo): LlmProviderEntity {
        return LlmProviderEntity(
            id = provider.id,
            name = provider.name,
            type = provider.type.name,
            baseUrl = SecretCipher.encrypt(provider.baseUrl),
            authJson = SecretCipher.encrypt(json.encodeToString(provider.auth)),
            configJson = SecretCipher.encrypt(json.encodeToString(provider.config)),
            icon = provider.icon,
            sortOrder = provider.sortOrder
        )
    }
}

