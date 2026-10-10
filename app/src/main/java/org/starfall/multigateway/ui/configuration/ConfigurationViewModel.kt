package org.starfall.multigateway.ui.configuration

import org.starfall.multigateway.data.repository.LocalWriteErrors
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.starfall.multigateway.data.local.preferences.AppPreferencesRepository
import org.starfall.multigateway.data.local.preferences.AppPreferences
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.repository.*

class ConfigurationViewModel(
    private val profileRepo: ProfileRepository,
    private val llmRepo: LlmRepository,
    private val mcpRepo: McpRepository,
    private val speechRepo: SpeechRepository,
    private val prefsRepo: AppPreferencesRepository,
) : ViewModel() {
    private val appPreferences = prefsRepo.appPreferencesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())
    val speechServices = speechRepo.allServices
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val providerGroups = llmRepo.allGroups
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importedProviderId = MutableStateFlow<String?>(null)
    val importedProviderId = _importedProviderId.asStateFlow()
    private val _providerImportError = MutableStateFlow<String?>(null)
    val providerImportError = _providerImportError.asStateFlow()

    fun importProvider(provider: LlmProviderInfo) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            try {
                llmRepo.saveProvider(provider)
                _importedProviderId.value = provider.id
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                _providerImportError.value = "Could not save the imported provider. Please try again."
            }
        }
    }

    suspend fun importProviders(providers: List<LlmProviderInfo>) {
        providers.forEach { llmRepo.saveProvider(it) }
    }

    fun providerImportOpened(id: String) { _importedProviderId.compareAndSet(id, null) }
    fun clearProviderImportError() { _providerImportError.value = null }

    private val _mcpToolsCache = MutableStateFlow<Map<String, List<ToolDefinition>>>(emptyMap())
    val mcpToolsCache: StateFlow<Map<String, List<ToolDefinition>>> = _mcpToolsCache.asStateFlow()

    private val _mcpToolErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val mcpToolErrors: StateFlow<Map<String, String>> = _mcpToolErrors.asStateFlow()

    private val _mcpToolsLoading = MutableStateFlow<Set<String>>(emptySet())
    val mcpToolsLoading: StateFlow<Set<String>> = _mcpToolsLoading.asStateFlow()

    suspend fun discoverTools(server: McpInfo): List<ToolDefinition> =
        _mcpToolsCache.value[server.id]
            ?: server.cachedTools
            ?: refreshMcpTools(server).getOrThrow()

    suspend fun refreshMcpTools(server: McpInfo): Result<List<ToolDefinition>> {
        _mcpToolsLoading.update { it + server.id }
        return try {
            val tools = mcpRepo.discoverTools(server)
            mcpRepo.saveCachedTools(server.id, tools)
            _mcpToolsCache.update { it + (server.id to tools) }
            _mcpToolErrors.update { it - server.id }
            Result.success(tools)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (error: Throwable) {
            val fullError = error.stackTraceToString().takeIf { it.isNotBlank() }
                ?: error.localizedMessage
                ?: "Unable to load tools"
            _mcpToolErrors.update { it + (server.id to fullError) }
            Result.failure(error)
        } finally {
            _mcpToolsLoading.update { it - server.id }
        }
    }

    fun clearMcpToolsState(serverId: String) {
        _mcpToolsCache.update { it - serverId }
        _mcpToolErrors.update { it - serverId }
        _mcpToolsLoading.update { it - serverId }
    }

    fun saveProfile(profile: ChatProfile) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            profileRepo.saveProfile(profile)
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            profileRepo.deleteProfile(profileId)
            if (appPreferences.value.selectedProfileId == profileId) {
                prefsRepo.setSelectedProfileId(null)
            }
        }
    }

    fun saveModelConfiguration(providerId: String, modelId: String, config: ModelConfiguration) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            val provider = llmRepo.getProviderById(providerId) ?: return@launch
            llmRepo.saveProvider(provider.copy(config = provider.config.copy(
                modelConfigs = provider.config.modelConfigs + (modelId to config)
            )))
        }
    }

    /** Persists the level picked in the chat's selected-model sheet. Off ("none") only applies to the conversation; the model's on/off switch lives in its configuration. */
    fun setModelReasoningEffort(providerId: String, modelId: String, effort: String?) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            val provider = llmRepo.getProviderById(providerId) ?: return@launch
            val config = provider.config.modelConfigs[modelId] ?: return@launch
            val applied = if (effort.isNullOrBlank()) config.copy(reasoningEffort = null)
            else config.withConversationReasoning(effort, provider.type)
            if (applied.reasoningDisabled) return@launch
            val updated = applied.copy(thinkingLevel = applied.reasoningEffort)
            if (updated == config) return@launch
            llmRepo.saveProvider(provider.copy(config = provider.config.copy(
                modelConfigs = provider.config.modelConfigs + (modelId to updated)
            )))
        }
    }

    fun setSendThinkingContent(providerId: String, modelId: String, enabled: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            val provider = llmRepo.getProviderById(providerId) ?: return@launch
            val config = provider.config.modelConfigs[modelId] ?: return@launch
            if (config.sendThinkingContent == enabled) return@launch
            llmRepo.saveProvider(provider.copy(config = provider.config.copy(
                modelConfigs = provider.config.modelConfigs + (modelId to config.copy(sendThinkingContent = enabled))
            )))
        }
    }

    fun reorderProviderModels(providerId: String, modelIds: List<String>) {
        viewModelScope.launch(LocalWriteErrors.handler) { llmRepo.reorderProviderModels(providerId, modelIds) }
    }

    fun saveProviderModels(providerId: String, modelConfigs: Map<String, ModelConfiguration>) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            val provider = llmRepo.getProviderById(providerId) ?: return@launch
            val modelIds = modelConfigs.keys.toList()
            llmRepo.saveProvider(provider.copy(config = provider.config.copy(
                modelConfigs = modelConfigs,
                modelIds = modelIds
            )))
            val prefs = appPreferences.value
            if (prefs.selectedProviderId == providerId && prefs.selectedModelId !in modelIds) {
                prefsRepo.setSelectedModel(providerId, modelIds.firstOrNull().orEmpty())
            }
        }
    }

    fun saveProvider(provider: LlmProviderInfo) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            llmRepo.saveProvider(provider)
            val prefs = appPreferences.value
            val modelIds = provider.config.modelIds
            if (prefs.selectedProviderId == provider.id && modelIds != null && prefs.selectedModelId !in modelIds) {
                prefsRepo.setSelectedModel(provider.id, modelIds.firstOrNull().orEmpty())
            }
        }
    }

    fun deleteProvider(providerId: String) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            llmRepo.deleteProvider(providerId)
        }
    }

    fun saveProviderGroup(group: ProviderGroup) {
        viewModelScope.launch(LocalWriteErrors.handler) { llmRepo.saveGroup(group) }
    }

    fun deleteProviderGroup(groupId: String) {
        viewModelScope.launch(LocalWriteErrors.handler) { llmRepo.deleteGroup(groupId) }
    }

    suspend fun placeProvider(placement: ProviderPlacement): Result<Unit> = try {
        llmRepo.placeProvider(placement)
        Result.success(Unit)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        Result.failure(failure)
    }

    /** Start a provider placement from the application-scoped ViewModel. The provider screen may
     * be removed immediately after a drop, so the write must not be owned by its composition scope. */
    fun queueProviderPlacement(placement: ProviderPlacement) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            llmRepo.placeProvider(placement)
        }
    }

    fun moveProviderToGroup(providerId: String, groupId: String?) {
        viewModelScope.launch(LocalWriteErrors.handler) { llmRepo.moveProviderToGroup(providerId, groupId) }
    }

    fun reorderProviderGroups(ids: List<String>) {
        viewModelScope.launch(LocalWriteErrors.handler) { llmRepo.reorderGroups(ids) }
    }

    fun reorderProviderRootItems(items: List<ProviderRootOrderItem>) {
        viewModelScope.launch(LocalWriteErrors.handler) { llmRepo.reorderRootItems(items) }
    }

    private val oauthAuthorizations = OAuthAuthorizations(viewModelScope, llmRepo::authorizeProvider)

    suspend fun authorizeProvider(provider: LlmProviderInfo): Result<LlmProviderInfo> =
        oauthAuthorizations.authorize(provider)

    fun cancelProviderAuthorization(provider: LlmProviderInfo) {
        oauthAuthorizations.cancel(provider)
    }

    suspend fun clearOAuthCredentials(provider: LlmProviderInfo): Result<LlmProviderInfo> =
        llmRepo.clearOAuthCredentials(provider)

    suspend fun testConnection(provider: LlmProviderInfo, modelId: String): Result<String> {
        return llmRepo.testModel(provider, modelId)
    }

    suspend fun fetchProviderModels(provider: LlmProviderInfo): List<DiscoveredModel> =
        llmRepo.fetchProviderModelCatalog(provider)

    suspend fun fetchOllamaModels(baseUrl: String): List<String> {
        return llmRepo.fetchOllamaModels(baseUrl)
    }

    suspend fun authorizeMcpOAuth(server: McpInfo): Result<McpInfo> =
        mcpRepo.authorizeOAuth(server)

    suspend fun clearMcpOAuth(server: McpInfo): McpInfo =
        mcpRepo.clearOAuth(server)

    fun saveMcpServer(server: McpInfo) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            mcpRepo.saveServer(server)
            refreshMcpTools(server)
        }
    }

    suspend fun importMcpServers(servers: List<McpInfo>) {
        servers.forEach { mcpRepo.saveServer(it) }
    }

    fun deleteMcpServer(serverId: String) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            mcpRepo.deleteServer(serverId)
            clearMcpToolsState(serverId)
        }
    }
    fun saveSpeechService(service: SpeechService) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            speechRepo.saveService(service)
        }
    }

    suspend fun importSpeechServices(services: List<SpeechService>) {
        services.forEach { speechRepo.saveService(it) }
    }

    fun deleteSpeechService(serviceId: String) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            speechRepo.deleteService(serviceId)
        }
    }

    fun reorderProfiles(ids: List<String>) {
        viewModelScope.launch(LocalWriteErrors.handler) { profileRepo.reorderProfiles(ids) }
    }

    fun reorderProviders(ids: List<String>) {
        viewModelScope.launch(LocalWriteErrors.handler) { llmRepo.reorderProviders(ids) }
    }

    fun reorderMcpServers(ids: List<String>) {
        viewModelScope.launch(LocalWriteErrors.handler) { mcpRepo.reorderServers(ids) }
    }

    fun reorderSpeechServices(ids: List<String>) {
        viewModelScope.launch(LocalWriteErrors.handler) { speechRepo.reorderServices(ids) }
    }

    fun setShowProfilesAsGrid(isGrid: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { prefsRepo.setShowProfilesAsGrid(isGrid) }
    }

    fun setShowProvidersAsGrid(isGrid: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { prefsRepo.setShowProvidersAsGrid(isGrid) }
    }

    fun setShowMcpAsGrid(isGrid: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { prefsRepo.setShowMcpAsGrid(isGrid) }
    }

    fun setShowSpeechAsGrid(isGrid: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) { prefsRepo.setShowSpeechAsGrid(isGrid) }
    }
}
