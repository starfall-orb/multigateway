package org.starfall.multigateway.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import org.starfall.multigateway.data.local.db.AppDatabase
import org.starfall.multigateway.data.local.db.SecretCipher
import org.starfall.multigateway.data.local.db.entities.*
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.IconStore
import org.starfall.multigateway.data.service.LlmService

class LlmRepository(private val db: AppDatabase, private val service: LlmService, private val icons: IconStore? = null) {
    suspend fun testConnection(provider: LlmProviderInfo) = service.testConnection(provider)
    suspend fun testModel(provider: LlmProviderInfo, modelId: String) = service.testModel(provider, modelId)
    suspend fun fetchProviderModelCatalog(provider: LlmProviderInfo) = service.fetchProviderModelCatalog(provider)
    suspend fun fetchProviderModels(provider: LlmProviderInfo) = service.fetchProviderModels(provider)
    suspend fun fetchOllamaModels(baseUrl: String) = service.fetchOllamaModels(baseUrl)

    private val providerDao = db.llmProviderDao()
    private val groupDao = db.providerGroupDao()
    private val modelsDao = db.llmModelsDao()

    val allProviders: Flow<List<LlmProviderInfo>> = providerDao.getAllProviders().map { entities ->
        entities.map { providerEntityToModel(it) }
    }

    val allGroups: Flow<List<ProviderGroup>> = groupDao.getAllGroups().map { groups ->
        groups.map { ProviderGroup(it.id, it.name, it.sortOrder, it.icon) }
    }

    suspend fun getProviderById(id: String): LlmProviderInfo? {
        val entity = providerDao.getProviderById(id) ?: return null
        return providerEntityToModel(entity)
    }

    suspend fun saveGroup(group: ProviderGroup) {
        group.icon?.let { icons?.cache(group.name, it) }
        groupDao.insertOrUpdate(ProviderGroupEntity(group.id, group.name.trim(), group.sortOrder, group.icon))
    }

    suspend fun deleteGroup(id: String) {
        db.withTransaction {
            val children = providerDao.getProvidersByGroup(id)
            val rootMax = maxOf(
                groupDao.getAllGroupsOnce().filterNot { it.id == id }.maxOfOrNull { it.sortOrder } ?: -1,
                providerDao.getUngroupedProviders().maxOfOrNull { it.sortOrder } ?: -1
            )
            children.forEachIndexed { index, provider ->
                providerDao.updateGroupAndSortOrder(provider.id, null, rootMax + index + 1)
            }
            groupDao.deleteById(id)
        }
    }

    suspend fun moveProviderToGroup(providerId: String, groupId: String?) {
        db.withTransaction {
            val sortOrder = if (groupId == null) {
                maxOf(
                    groupDao.getAllGroupsOnce().maxOfOrNull { it.sortOrder } ?: -1,
                    providerDao.getUngroupedProviders().filterNot { it.id == providerId }.maxOfOrNull { it.sortOrder } ?: -1
                ) + 1
            } else {
                (providerDao.getProvidersByGroup(groupId).filterNot { it.id == providerId }.maxOfOrNull { it.sortOrder } ?: -1) + 1
            }
            providerDao.updateGroupAndSortOrder(providerId, groupId, sortOrder)
        }
    }

    suspend fun reorderRootItems(items: List<ProviderRootOrderItem>) {
        db.withTransaction {
            items.forEachIndexed { index, item ->
                if (item.isGroup) groupDao.updateSortOrder(item.id, index)
                else providerDao.updateSortOrder(item.id, index)
            }
        }
    }

    suspend fun reorderGroups(ids: List<String>) {
        ids.forEachIndexed { index, id -> groupDao.updateSortOrder(id, index) }
    }

    suspend fun authorizeProvider(provider: LlmProviderInfo): Result<LlmProviderInfo> = runCatching {
        service.withOAuthSession {
            val authorized = service.authorizeProvider(provider).getOrThrow()
            val persisted = providerDao.getProviderById(provider.id)?.let(::providerEntityToModel)
            providerDao.insertOrUpdate(providerModelToEntity(
                withSavedIcons(persisted?.copy(type = authorized.type, baseUrl = authorized.baseUrl, auth = authorized.auth)
                    ?: authorized, persisted)
            ))
            authorized
        }
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
        providerDao.insertOrUpdate(providerModelToEntity(withSavedIcons(provider, previous)))
    }

    private fun withSavedIcons(provider: LlmProviderInfo, previous: LlmProviderInfo?): LlmProviderInfo {
        provider.icon?.takeIf { it != previous?.icon || provider.name != previous?.name }?.let {
            icons?.cache(provider.name, it)
        }
        provider.config.modelConfigs.forEach { (id, config) ->
            val old = previous?.config?.modelConfigs?.get(id)
            config.icon?.takeIf { it != old?.icon || config.displayName != old?.displayName }?.let {
                icons?.cache(config.displayName.ifBlank { id }, it, model = true)
            }
        }
        return provider
    }

    suspend fun reorderProviderModels(providerId: String, modelIds: List<String>) {
        db.withTransaction {
            val entity = providerDao.getProviderById(providerId) ?: return@withTransaction
            val provider = providerEntityToModel(entity)
            providerDao.insertOrUpdate(providerModelToEntity(
                provider.copy(config = provider.config.reorderedModels(modelIds))
            ))
        }
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
            sortOrder = entity.sortOrder,
            groupId = entity.groupId
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
            sortOrder = provider.sortOrder,
            groupId = provider.groupId
        )
    }
}