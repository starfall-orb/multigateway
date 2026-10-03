package org.starfall.multigateway.data.repository

import kotlinx.coroutines.flow.first
import org.starfall.multigateway.data.local.preferences.AppPreferencesRepository
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.IconStore

/** Runs once per application process; existing records retain their persisted format. */
class DefaultDataInitializer(
    private val llmRepo: LlmRepository,
    private val mcpRepo: McpRepository,
    private val speechRepo: SpeechRepository,
    private val prefsRepo: AppPreferencesRepository,
    private val icons: IconStore,
) {
    suspend fun initialize() {
        llmRepo.allProviders.first().let { currentProviders ->
            if (currentProviders.isEmpty()) {
                initDefaultProviders()
            } else {
                val legacyGoogle = currentProviders.find { it.id == "google" }
                if (legacyGoogle?.auth?.method == AuthMethod.QUERY_PARAM && legacyGoogle.auth.key.isNullOrBlank()) {
                    llmRepo.saveProvider(legacyGoogle.copy(auth = legacyGoogle.auth.copy(key = "key")))
                }
                val legacyAnthropic = currentProviders.find { it.id == "anthropic" }
                if (legacyAnthropic?.auth?.method == AuthMethod.CUSTOM_HEADER && legacyAnthropic.auth.key.isNullOrBlank()) {
                    llmRepo.saveProvider(legacyAnthropic.copy(auth = Authorization(
                        method = AuthMethod.PLATFORM_DEFAULT,
                        value = legacyAnthropic.auth.value.orEmpty()
                    )))
                }

                currentProviders.filter {
                    it.type in listOf(ProviderType.GOOGLE, ProviderType.ANTHROPIC) &&
                        it.auth.method == AuthMethod.BEARER_TOKEN && it.auth.key == null
                }.forEach {
                    llmRepo.saveProvider(it.copy(auth = it.auth.copy(method = AuthMethod.PLATFORM_DEFAULT)))
                }

                val existingOllama = currentProviders.find { it.type == ProviderType.OLLAMA }
                if (existingOllama != null && (
                        existingOllama.baseUrl.contains("108.181.196.208") ||
                        existingOllama.baseUrl.contains("10.0.2.2") ||
                        existingOllama.baseUrl.contains("localhost")
                    )) {
                    llmRepo.saveProvider(
                        existingOllama.copy(
                            name = "Ollama",
                            baseUrl = "https://ollama.com/api"
                        )
                    )
                }
            }
        }

        val prefs = prefsRepo.appPreferencesFlow.first()
        if (!prefs.mcpPresetsInitialized) {
            initDefaultMcpServers()
            prefsRepo.setMcpPresetsInitialized(true)
        }

        speechRepo.allServices.first().let { currentServices ->
            if (currentServices.isEmpty()) {
                initDefaultSpeechServices()
            }
        }

        pruneUnusedIcons()
    }

    private suspend fun pruneUnusedIcons() {
        val entityImages = buildList {
            llmRepo.allProviders.first().forEach { provider ->
                add(provider.icon)
                provider.config.modelConfigs.values.forEach { add(it.icon) }
            }
            llmRepo.allGroups.first().forEach { add(it.icon) }
            mcpRepo.allServers.first().forEach { add(it.icon) }
        }
        icons.prune(entityImages)
    }

    private suspend fun initDefaultProviders() {
        // No default provider presets
    }

    private suspend fun initDefaultMcpServers() {
        // No default MCP server presets
    }

    private suspend fun initDefaultSpeechServices() {
        val systemTts = SpeechService(
            id = "system_tts",
            name = "Android System TTS",
            provider = "system",
            voice = "Default",
            speed = 1.0f,
            pitch = 1.0f
        )
        speechRepo.saveService(systemTts)
    }

}
