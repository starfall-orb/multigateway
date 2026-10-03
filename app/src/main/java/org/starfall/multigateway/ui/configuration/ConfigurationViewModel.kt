package org.starfall.multigateway.ui.configuration

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
        viewModelScope.launch {
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
        viewModelScope.launch {
            profileRepo.saveProfile(profile)
        }
    }

    fun deleteProfile(profileId: String) {
        viewModelScope.launch {
            profileRepo.deleteProfile(profileId)
            if (appPreferences.value.selectedProfileId == profileId) {
                prefsRepo.setSelectedProfileId(null)
            }
        }
    }

    fun saveModelConfiguration(providerId: String, modelId: String, config: ModelConfiguration) {
        viewModelScope.launch {
            val provider = llmRepo.getProviderById(providerId) ?: return@launch
            llmRepo.saveProvider(provider.copy(config = provider.config.copy(
                modelConfigs = provider.config.modelConfigs + (modelId to config)
            )))
        }
    }

    fun reorderProviderModels(providerId: String, modelIds: List<String>) {
        viewModelScope.launch { llmRepo.reorderProviderModels(providerId, modelIds) }
    }

    fun saveProviderModels(providerId: String, modelConfigs: Map<String, ModelConfiguration>) {
        viewModelScope.launch {
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
        viewModelScope.launch {
            llmRepo.saveProvider(provider)
            val prefs = appPreferences.value
            val modelIds = provider.config.modelIds
            if (prefs.selectedProviderId == provider.id && modelIds != null && prefs.selectedModelId !in modelIds) {
                prefsRepo.setSelectedModel(provider.id, modelIds.firstOrNull().orEmpty())
            }
        }
    }

    fun deleteProvider(providerId: String) {
        viewModelScope.launch {
            llmRepo.deleteProvider(providerId)
        }
    }

    fun saveProviderGroup(group: ProviderGroup) {
        viewModelScope.launch { llmRepo.saveGroup(group) }
    }

    fun deleteProviderGroup(groupId: String) {
        viewModelScope.launch { llmRepo.deleteGroup(groupId) }
    }

    fun moveProviderToGroup(providerId: String, groupId: String?) {
        viewModelScope.launch { llmRepo.moveProviderToGroup(providerId, groupId) }
    }

    fun reorderProviderGroups(ids: List<String>) {
        viewModelScope.launch { llmRepo.reorderGroups(ids) }
    }

    fun reorderProviderRootItems(items: List<ProviderRootOrderItem>) {
        viewModelScope.launch { llmRepo.reorderRootItems(items) }
    }

    private val oauthAuthorizations = OAuthAuthorizations(viewModelScope, llmRepo::authorizeProvider)

    suspend fun authorizeProvider(provider: LlmProviderInfo): Result<LlmProviderInfo> =
        oauthAuthorizations.authorize(provider)

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
        viewModelScope.launch {
            mcpRepo.saveServer(server)
            refreshMcpTools(server)
        }
    }

    fun deleteMcpServer(serverId: String) {
        viewModelScope.launch {
            mcpRepo.deleteServer(serverId)
            clearMcpToolsState(serverId)
        }
    }
    fun saveSpeechService(service: SpeechService) {
        viewModelScope.launch {
            speechRepo.saveService(service)
        }
    }

    fun deleteSpeechService(serviceId: String) {
        viewModelScope.launch {
            speechRepo.deleteService(serviceId)
        }
    }

    fun reorderProfiles(ids: List<String>) {
        viewModelScope.launch { profileRepo.reorderProfiles(ids) }
    }

    fun reorderProviders(ids: List<String>) {
        viewModelScope.launch { llmRepo.reorderProviders(ids) }
    }

    fun reorderMcpServers(ids: List<String>) {
        viewModelScope.launch { mcpRepo.reorderServers(ids) }
    }

    fun reorderSpeechServices(ids: List<String>) {
        viewModelScope.launch { speechRepo.reorderServices(ids) }
    }

    fun setShowProfilesAsGrid(isGrid: Boolean) {
        viewModelScope.launch { prefsRepo.setShowProfilesAsGrid(isGrid) }
    }

    fun setShowProvidersAsGrid(isGrid: Boolean) {
        viewModelScope.launch { prefsRepo.setShowProvidersAsGrid(isGrid) }
    }

    fun setShowMcpAsGrid(isGrid: Boolean) {
        viewModelScope.launch { prefsRepo.setShowMcpAsGrid(isGrid) }
    }

    fun setShowSpeechAsGrid(isGrid: Boolean) {
        viewModelScope.launch { prefsRepo.setShowSpeechAsGrid(isGrid) }
    }
}
