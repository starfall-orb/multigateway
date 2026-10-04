package org.starfall.multigateway.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    internal fun rememberExistingModelConfigurations(providers: List<LlmProviderInfo>) {
        providers.flatMap { it.config.modelConfigs.entries }
            .sortedBy { if (it.value.modelType == ModelType.TEXT_GENERATION) 1 else 0 }
            .forEach { (id, config) -> service.modelConfigurationMemory.remember(id, config, onlyIfMissing = true) }
    }

    private val providerDao = db.llmProviderDao()
    private val groupDao = db.providerGroupDao()
    private val modelsDao = db.llmModelsDao()

    private val storedProviders: Flow<List<LlmProviderInfo>> = providerDao.getAllProviders().map { entities ->
        entities.map { providerEntityToModel(it) }
    }

    private val storedGroups: Flow<List<ProviderGroup>> = groupDao.getAllGroups().map { groups ->
        groups.map { ProviderGroup(it.id, it.name, it.sortOrder, it.icon) }
    }

    private data class Catalog(val providers: List<LlmProviderInfo>, val groups: List<ProviderGroup>)
    private val state = ImmediateState(combine(storedProviders, storedGroups, ::Catalog))
    val allProviders: Flow<List<LlmProviderInfo>> = state.flow.map { it.providers }.distinctUntilChanged()
    val allGroups: Flow<List<ProviderGroup>> = state.flow.map { it.groups }.distinctUntilChanged()

    suspend fun getProviderById(id: String): LlmProviderInfo? {
        state.value?.let { return it.providers.firstOrNull { p -> p.id == id } }
        return withContext(Dispatchers.IO) {
            providerDao.getProviderById(id)?.let(::providerEntityToModel)
        }
    }

    suspend fun saveGroup(group: ProviderGroup) {
        state.mutate({ it.copy(groups = it.groups.upsert(group.copy(name = group.name.trim())) { g -> g.id }.sortedBy { g -> g.sortOrder }) }) {
            groupDao.insertOrUpdate(ProviderGroupEntity(group.id, group.name.trim(), group.sortOrder, group.icon))
        }
    }

    suspend fun deleteGroup(id: String) {
        state.mutate({ catalog ->
            val rootMax = maxOf(catalog.groups.filterNot { it.id == id }.maxOfOrNull { it.sortOrder } ?: -1,
                catalog.providers.filter { it.groupId == null }.maxOfOrNull { it.sortOrder } ?: -1)
            val children = catalog.providers.filter { it.groupId == id }.sortedBy { it.sortOrder }.map { it.id }
            catalog.copy(groups = catalog.groups.filterNot { it.id == id },
                providers = catalog.providers.map { p -> if (p.groupId == id) p.copy(groupId = null, sortOrder = rootMax + children.indexOf(p.id) + 1) else p })
        }) {
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
    }

    suspend fun moveProviderToGroup(providerId: String, groupId: String?) {
        state.mutate({ catalog ->
            val max = maxOf(catalog.providers.filter { it.groupId == groupId && it.id != providerId }.maxOfOrNull { it.sortOrder } ?: -1,
                if (groupId == null) catalog.groups.maxOfOrNull { it.sortOrder } ?: -1 else -1)
            catalog.copy(providers = catalog.providers.map { if (it.id == providerId) it.copy(groupId = groupId, sortOrder = max + 1) else it })
        }) {
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
    }

    suspend fun reorderRootItems(items: List<ProviderRootOrderItem>) {
        state.mutate({ catalog -> catalog.copy(
            providers = catalog.providers.map { p -> items.indexOfFirst { !it.isGroup && it.id == p.id }.takeIf { it >= 0 }?.let { p.copy(sortOrder = it) } ?: p },
            groups = catalog.groups.map { g -> items.indexOfFirst { it.isGroup && it.id == g.id }.takeIf { it >= 0 }?.let { g.copy(sortOrder = it) } ?: g })
        }) {
            db.withTransaction {
                items.forEachIndexed { index, item ->
                    if (item.isGroup) groupDao.updateSortOrder(item.id, index)
                    else providerDao.updateSortOrder(item.id, index)
                }
            }
        }
    }

    suspend fun reorderGroups(ids: List<String>) {
        state.mutate({ catalog -> catalog.copy(groups = catalog.groups.map { g -> ids.indexOf(g.id).takeIf { it >= 0 }?.let { g.copy(sortOrder = it) } ?: g }.sortedBy { it.sortOrder }) }) {
            db.withTransaction {
                ids.forEachIndexed { index, id -> groupDao.updateSortOrder(id, index) }
            }
        }
    }

    suspend fun authorizeProvider(provider: LlmProviderInfo): Result<LlmProviderInfo> = runCatching {
        service.withOAuthSession {
            val authorized = service.authorizeProvider(provider).getOrThrow()
            withContext(Dispatchers.IO) {
                val persisted = providerDao.getProviderById(provider.id)?.let(::providerEntityToModel)
                providerDao.insertOrUpdate(providerModelToEntity(
                    persisted?.copy(type = authorized.type, baseUrl = authorized.baseUrl, auth = authorized.auth)
                        ?: authorized
                ))
            }
            authorized
        }
    }

    suspend fun clearOAuthCredentials(provider: LlmProviderInfo): Result<LlmProviderInfo> = withContext(Dispatchers.IO) {
        runCatching {
            service.clearAccountCredentials(provider.type, provider.id)
            val clearedAuth = Authorization(method = AuthMethod.OAUTH)
            providerDao.getProviderById(provider.id)?.let(::providerEntityToModel)?.let { persisted ->
                providerDao.insertOrUpdate(providerModelToEntity(persisted.copy(auth = clearedAuth)))
            }
            provider.copy(auth = clearedAuth)
        }
    }

    suspend fun saveProvider(provider: LlmProviderInfo) {
        state.mutate({ it.copy(providers = it.providers.upsert(provider) { p -> p.id }.sortedBy { p -> p.sortOrder }) }) {
            val previous = providerDao.getProviderById(provider.id)?.let(::providerEntityToModel)
            if (
                previous != null &&
                (previous.type != provider.type ||
                    (previous.auth.method == AuthMethod.OAUTH && provider.auth.method != AuthMethod.OAUTH))
            ) {
                service.clearAccountCredentials(previous.type, previous.id)
            }
            providerDao.insertOrUpdate(providerModelToEntity(provider))
            provider.config.modelConfigs.forEach { (id, config) ->
                if (previous?.config?.modelConfigs?.get(id) != config) service.modelConfigurationMemory.remember(id, config)
            }
        }
    }

    suspend fun reorderProviderModels(providerId: String, modelIds: List<String>) {
        state.mutate({ catalog -> catalog.copy(providers = catalog.providers.map { if (it.id == providerId) it.copy(config = it.config.reorderedModels(modelIds)) else it }) }) {
            db.withTransaction {
                val entity = providerDao.getProviderById(providerId) ?: return@withTransaction
                val provider = providerEntityToModel(entity)
                providerDao.insertOrUpdate(providerModelToEntity(
                    provider.copy(config = provider.config.reorderedModels(modelIds))
                ))
            }
        }
    }

    suspend fun deleteProvider(id: String) {
        state.mutate({ it.copy(providers = it.providers.filterNot { p -> p.id == id }) }) {
            providerDao.getProviderById(id)?.let(::providerEntityToModel)?.let {
                service.clearAccountCredentials(it.type, it.id)
            }
            providerDao.deleteById(id)
        }
    }

    suspend fun reorderProviders(ids: List<String>) {
        state.mutate({ catalog -> catalog.copy(providers = catalog.providers.map { p -> ids.indexOf(p.id).takeIf { it >= 0 }?.let { p.copy(sortOrder = it) } ?: p }.sortedBy { it.sortOrder }) }) {
            db.withTransaction {
                ids.forEachIndexed { index, id -> providerDao.updateSortOrder(id, index) }
            }
        }
    }

    suspend fun getModelsForProvider(providerId: String): LlmProviderModels? = withContext(Dispatchers.IO) {
        val entity = modelsDao.getModelsForProvider(providerId) ?: return@withContext null
        val models: List<LlmModel> = try {
            json.decodeFromString(entity.modelsJson)
        } catch (e: Exception) {
            emptyList()
        }
        LlmProviderModels(id = entity.id, models = models)
    }

    suspend fun saveModelsForProvider(providerModels: LlmProviderModels) = withContext(Dispatchers.IO) {
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
