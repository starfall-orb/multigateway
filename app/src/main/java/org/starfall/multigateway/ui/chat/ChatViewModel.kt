package org.starfall.multigateway.ui.chat

import org.starfall.multigateway.data.repository.LocalWriteErrors
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.starfall.multigateway.data.local.preferences.AppPreferences
import org.starfall.multigateway.data.local.preferences.AppPreferencesRepository
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.repository.*
import org.starfall.multigateway.data.service.TtsHelper
import org.starfall.multigateway.data.service.SpeechAudioPlayer
import org.starfall.multigateway.data.service.SpeechSynthesisService
import java.util.UUID
import org.starfall.multigateway.data.tools.*

internal fun shouldGenerateConversationTitle(userText: String, messageCount: Int): Boolean =
    userText.trim().split(Regex("\\s+")).count { it.isNotEmpty() } >= 10 || messageCount >= 5

internal fun Conversation.startedWithDirectMedia(): Boolean =
    messages.firstOrNull { it.role == ChatRole.MODEL }?.activeVersion?.toolActivity?.any {
        it.name == "generate_image" || it.name == "generate_video"
    } == true

class ChatViewModel(
    private val conversationRepo: ConversationRepository,
    private val llmRepo: LlmRepository,
    private val mcpRepo: McpRepository,
    private val prefsRepo: AppPreferencesRepository,
    private val toolChat: ToolChat,
    private val toolStore: ToolSettingsStore,
    private val speechRepo: SpeechRepository,
    private val ttsHelper: TtsHelper,
    private val speechSynthesis: SpeechSynthesisService,
    private val speechAudioPlayer: SpeechAudioPlayer,
) : ViewModel() {

    val toolSettings = toolStore.settings.stateIn(viewModelScope, SharingStarted.Eagerly, ToolSettings())
    fun setSystemTool(name: String, config: SystemToolConfig) { viewModelScope.launch(LocalWriteErrors.handler) { toolStore.update { it.copy(system = it.system + (name to config)) } } }
    fun setQuickMcp(id: String, enabled: Boolean) { viewModelScope.launch(LocalWriteErrors.handler) { toolStore.update { it.copy(quickMcp = it.quickMcp + (id to enabled)) } } }
    fun setMcpToolEnabled(serverId: String, toolName: String, enabled: Boolean) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            toolStore.update { settings ->
                val serverTools = settings.mcpTools[serverId].orEmpty() + (toolName to enabled)
                settings.copy(mcpTools = settings.mcpTools + (serverId to serverTools))
            }
        }
    }
    private fun toolEvents(provider: LlmProviderInfo, model: String, messages: List<StoredMessage>, prompt: String) =
        toolChat.generate(provider, model, messages, prompt, mcpServers.value, providers.value,
            settings = { toolSettings.value })

    private val deletingAllConversations = MutableStateFlow(false)
    private val deletingConversations = MutableStateFlow<Set<String>>(emptySet())
    private val renamingConversations = MutableStateFlow<Map<String, String>>(emptyMap())
    val conversations: StateFlow<List<Conversation>> = combine(
        conversationRepo.allConversations, deletingConversations, renamingConversations, deletingAllConversations
    ) { items, deleted, renamed, deletingAll ->
        if (deletingAll) emptyList() else items.filterNot { it.id in deleted }.map { item ->
            renamed[item.id]?.let { item.copy(title = it) } ?: item
        }
    }
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val providers: StateFlow<List<LlmProviderInfo>> = llmRepo.allProviders
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val mcpServers: StateFlow<List<McpInfo>> = mcpRepo.allServers
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val speechServices: StateFlow<List<SpeechService>> = speechRepo.allServices
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val appPreferences: StateFlow<AppPreferences> = prefsRepo.appPreferencesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPreferences())

    private val _currentConversation = MutableStateFlow<Conversation?>(null)
    val currentConversation: StateFlow<Conversation?> = _currentConversation.asStateFlow()

    private val summaries = ConversationSummaryCoordinator(conversationRepo, toolChat, { _currentConversation.value }) { updated ->
        if (_currentConversation.value?.id == updated.id) {
            _currentConversation.value = renamingConversations.value[updated.id]?.let { updated.copy(title = it) } ?: updated
        }
    }
    val summaryProgress = summaries.progress
    private var summaryJob: Job? = null

    private fun providerWithReasoning(
        provider: LlmProviderInfo,
        modelId: String,
        conversation: Conversation
    ): LlmProviderInfo {
        val effort = conversation.reasoningEffort?.takeIf { it.isNotBlank() } ?: return provider
        val config = provider.config.modelConfigs[modelId] ?: return provider
        if (!config.supportsThinking) return provider
        return provider.copy(
            config = provider.config.copy(
                modelConfigs = provider.config.modelConfigs +
                    (modelId to config.withConversationReasoning(effort, provider.type))
            )
        )
    }

    private fun summaryAfterMessageMutation(
        conversation: Conversation,
        changedMessageIndex: Int,
        newMessages: List<StoredMessage>
    ): ConversationSummary? {
        val summary = conversation.summary ?: return null
        val boundaryIndex = conversation.messages.indexOfFirst { it.id == summary.throughMessageId }
        if (boundaryIndex < 0 || changedMessageIndex <= boundaryIndex) return null
        return summary.takeIf { candidate ->
            newMessages.any { it.id == candidate.throughMessageId }
        }
    }

    private fun configuredTextModel(name: String): Pair<LlmProviderInfo, SystemToolConfig>? {
        val config = toolSettings.value.system[name] ?: return null
        val provider = providers.value.find { it.id == config.providerId } ?: return null
        val model = provider.config.modelConfigs[config.modelId] ?: return null
        if (model.modelType != ModelType.TEXT_GENERATION) return null
        return provider to config
    }

    private val generation = ChatGeneration(viewModelScope, conversationRepo::saveConversation) { updated ->
        if (_currentConversation.value?.id == updated.id) {
            _currentConversation.value = renamingConversations.value[updated.id]?.let { updated.copy(title = it) } ?: updated
        }
    }
    val isGenerating = generation.busy

    private data class ContextEstimate(
        val conversation: Conversation?,
        val preferences: AppPreferences,
        val model: ModelConfiguration?,
        val status: ContextWindowStatus?,
    )

    private val contextEstimate: StateFlow<ContextEstimate?> = combine(_currentConversation, appPreferences, providers) { conversation, prefs, available ->
        val config = available.find { it.id == prefs.selectedProviderId }?.config?.modelConfigs?.get(prefs.selectedModelId)
        ContextEstimate(conversation, prefs, config,
            if (conversation == null || config == null || config.modelType != ModelType.TEXT_GENERATION) null
            else org.starfall.multigateway.ui.chat.contextWindowStatus(conversation, prefs.effectiveSystemPrompt, config, prefs.promptRoleMessages()))
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val contextWindowStatus: StateFlow<ContextWindowStatus?> = contextEstimate.map { it?.status }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var lastAutomaticSummaryAttempt: String? = null

    private fun summaryTextModel(): Pair<LlmProviderInfo, SystemToolConfig>? {
        configuredTextModel("chat_summary")?.let { return it }
        val prefs = appPreferences.value
        val provider = providers.value.find { it.id == prefs.selectedProviderId } ?: return null
        val model = provider.config.modelConfigs[prefs.selectedModelId] ?: return null
        if (model.modelType != ModelType.TEXT_GENERATION) return null
        return provider to SystemToolConfig(providerId = provider.id, modelId = prefs.selectedModelId)
    }

    private fun maybeStartAutomaticSummary(incoming: StoredMessage? = null): Boolean {
        if (isGenerating.value || summaryJob?.isActive == true || pendingConversationWrites > 0) return false
        val current = _currentConversation.value ?: return false
        val prefs = appPreferences.value
        val model = providers.value.find { it.id == prefs.selectedProviderId }?.config?.modelConfigs?.get(prefs.selectedModelId) ?: return false
        val status = contextEstimate.value?.takeIf {
            it.conversation === current && it.preferences == prefs && it.model == model
        }?.status ?: if (incoming != null) {
            org.starfall.multigateway.ui.chat.contextWindowStatus(current, prefs.effectiveSystemPrompt, model, prefs.promptRoleMessages())
        } else return false
        val projected = status.copy(estimatedTokens = status.estimatedTokens + (incoming?.let { estimateMessageTokens(it, false) } ?: 0))
        if (model.modelType != ModelType.TEXT_GENERATION || !projected.shouldAutoSummarize) return false
        val boundary = current.messages.lastOrNull()?.id ?: return false
        if (current.summary?.throughMessageId == boundary) return false
        val attempt = "${current.id}/$boundary/${current.summary?.id}/${prefs.selectedProviderId}/${prefs.selectedModelId}"
        if (lastAutomaticSummaryAttempt == attempt) return false
        val helper = summaryTextModel() ?: return false
        val helperWindow = helper.first.config.modelConfigs[helper.second.modelId]?.contextWindowTokens?.coerceAtLeast(1) ?: DEFAULT_CONTEXT_WINDOW_TOKENS
        lastAutomaticSummaryAttempt = attempt
        return startConversationSummary(ConversationSummaryRequest(
            targetTokens = minOf(1200, (helperWindow / 8).coerceAtLeast(1)),
            chunked = true,
            tokensPerChunk = minOf(20_000, (helperWindow * 0.6).toInt().coerceAtLeast(1))
        ))
    }

    private val _queuedMessages = MutableStateFlow<List<StoredMessage>>(emptyList())
    val queuedMessages: StateFlow<List<StoredMessage>> = _queuedMessages.asStateFlow()

    init {
        viewModelScope.launch { contextEstimate.collect { maybeStartAutomaticSummary() } }
        viewModelScope.launch {
            isGenerating.collect { busy ->
                if (!busy && !maybeStartAutomaticSummary()) drainQueuedMessages()
            }
        }
    }

    private fun drainQueuedMessages() {
        if (isGenerating.value || summaryJob?.isActive == true || pendingConversationWrites > 0) return
        if (maybeStartAutomaticSummary()) return
        val next = _queuedMessages.value.firstOrNull() ?: return
        _queuedMessages.value = _queuedMessages.value.drop(1)
        sendMessage(next.content, next.files)
    }

    fun editQueuedMessage(id: String, newContent: String, files: List<String>): Boolean {
        if ((newContent.isBlank() && files.isEmpty()) || _queuedMessages.value.none { it.id == id }) return false
        _queuedMessages.value = _queuedMessages.value.map { msg ->
            if (msg.id == id) {
                msg.copy(
                    versions = listOf(
                        MessageVersion(
                            content = newContent,
                            timestamp = System.currentTimeMillis().toString(),
                            files = files
                        )
                    )
                )
            } else {
                msg
            }
        }
        return true
    }

    fun deleteQueuedMessage(id: String) {
        _queuedMessages.value = _queuedMessages.value.filter { it.id != id }
    }
    val chatError = generation.error
    val generatingConversationId = generation.conversationId
    private var pendingConversationWrites = 0
    private val conversationWrites = Mutex()

    private fun writeConversation(serialize: Boolean = false, block: suspend () -> Unit) {
        pendingConversationWrites++
        viewModelScope.launch(LocalWriteErrors.handler) {
            try {
                if (serialize) conversationWrites.withLock { block() } else block()
            } finally {
                pendingConversationWrites--
                drainQueuedMessages()
            }
        }
    }

    private fun saveEditedConversation(previous: Conversation, updated: Conversation) {
        writeConversation {
            try {
                conversationRepo.saveConversation(updated)
            } catch (error: Exception) {
                if (_currentConversation.value == updated) {
                    _currentConversation.value = conversationRepo.getById(previous.id) ?: previous
                }
                throw error
            }
        }
    }

    fun selectConversation(conversation: Conversation) {
        if (deletingAllConversations.value || conversation.id in deletingConversations.value) return
        _queuedMessages.value = emptyList()
        _currentConversation.value = generation.snapshot?.takeIf { it.id == conversation.id } ?: conversation
    }

    fun startNewChat() {
        _queuedMessages.value = emptyList()
        val now = System.currentTimeMillis()
        _currentConversation.value = Conversation(
            id = UUID.randomUUID().toString(),
            title = "New Chat",
            createdAt = now,
            updatedAt = now
        )
    }

    fun deleteConversation(id: String) = deleteConversations(setOf(id))

    fun deleteConversations(ids: Set<String>) {
        val targets = ids - deletingConversations.value
        if (targets.isEmpty()) return
        val previous = _currentConversation.value?.takeIf { it.id in targets }
        deletingConversations.update { it + targets }
        if (previous != null) {
            _currentConversation.value = null
            _queuedMessages.value = emptyList()
        }
        writeConversation(serialize = true) {
            var deleted = false
            try {
                if (summaryProgress.value?.conversationId in targets) summaryJob?.cancelAndJoin()
                if (generation.snapshot?.id in targets) generation.stopAndJoin()
                conversationRepo.deleteConversations(targets.toList())
                deleted = true
                prefsRepo.updateSidebar { it.removeChats(targets) }
            } catch (error: Exception) {
                if (!deleted && _currentConversation.value == null && previous != null) {
                    _currentConversation.value = previous
                }
                throw error
            } finally {
                deletingConversations.update { it - targets }
            }
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        val previous = _currentConversation.value?.takeIf { it.id == id }
        renamingConversations.update { it + (id to newTitle) }
        previous?.let { _currentConversation.value = it.copy(title = newTitle) }
        writeConversation(serialize = true) {
            try {
                if (generation.snapshot?.id == id) generation.stopAndJoin()
                conversationRepo.renameConversation(id, newTitle)
            } catch (error: Exception) {
                if (_currentConversation.value?.id == id && _currentConversation.value?.title == newTitle) {
                    previous?.let { _currentConversation.value = _currentConversation.value?.copy(title = it.title) }
                }
                throw error
            } finally {
                renamingConversations.update { if (it[id] == newTitle) it - id else it }
            }
        }
    }

    fun clearAllConversations() {
        if (deletingAllConversations.value) return
        val previous = _currentConversation.value
        deletingAllConversations.value = true
        _currentConversation.value = null
        _queuedMessages.value = emptyList()
        writeConversation(serialize = true) {
            var deleted = false
            try {
                summaryJob?.cancelAndJoin()
                generation.stopAndJoin()
                conversationRepo.deleteAll()
                deleted = true
                prefsRepo.updateSidebar { SidebarOrganization() }
            } catch (error: Exception) {
                if (!deleted && _currentConversation.value == null) _currentConversation.value = previous
                throw error
            } finally {
                deletingAllConversations.value = false
            }
        }
    }

    fun deleteAllUserData() {
        clearAllConversations()
        viewModelScope.launch(LocalWriteErrors.handler) { prefsRepo.setSelectedProfileId(null) }
    }

    fun cleanCache() {
        // Clear cached responses and stopped streams
        stopGeneration()
        stopSpeaking()
    }


    fun setDefaultSystemPrompt(prompt: String) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            prefsRepo.setDefaultSystemPrompt(prompt)
        }
    }
    fun setPromptLibrary(library: PromptLibrary) { viewModelScope.launch(LocalWriteErrors.handler) { prefsRepo.setPromptLibrary(library) } }

    fun selectModel(providerId: String, modelId: String) {
        viewModelScope.launch(LocalWriteErrors.handler) {
            launch { prefsRepo.setSelectedModel(providerId, modelId) }
            val provider = llmRepo.getProviderById(providerId)
            if (provider?.config?.modelIds != null && modelId !in provider.config.modelIds) {
                llmRepo.saveProvider(provider.copy(config = provider.config.copy(
                    modelIds = provider.config.modelIds + modelId,
                    modelConfigs = provider.config.modelConfigs + (modelId to ModelConfiguration())
                )))
            }
        }
    }

    fun selectSpeechService(serviceId: String?) {
        viewModelScope.launch(LocalWriteErrors.handler) { prefsRepo.setSelectedSpeechServiceId(serviceId) }
    }

    private val speech = ChatSpeechCoordinator(viewModelScope, appPreferences, providers, speechServices,
        ttsHelper, speechSynthesis, speechAudioPlayer)
    val speakingMessageId = speech.speakingMessageId
    val activeTestSpeechServiceId = speech.activeTestServiceId
    fun speakText(text: String, messageId: String? = null) = speech.speak(text, messageId)
    fun testVoice(service: SpeechService, text: String) = speech.speakWithService(service, text, isTest = true)
    fun stopSpeaking() = speech.stop()

    fun setConversationReasoningEffort(effort: String?) {
        val current = _currentConversation.value ?: return
        val normalized = effort?.takeIf { it.isNotBlank() }
        val updated = current.copy(reasoningEffort = normalized, updatedAt = System.currentTimeMillis())
        _currentConversation.value = updated
        if (updated.messages.isNotEmpty()) writeConversation { conversationRepo.saveConversation(updated) }
    }

    fun setSummaryRole(role: SummaryRole) {
        val current = _currentConversation.value ?: return
        val summary = current.summary ?: return
        val updated = current.copy(
            summary = summary.copy(role = role),
            updatedAt = System.currentTimeMillis()
        )
        _currentConversation.value = updated
        writeConversation { conversationRepo.saveConversation(updated) }
    }

    fun deleteConversationSummary() {
        val current = _currentConversation.value ?: return
        if (current.summary == null) return
        val updated = current.copy(summary = null, updatedAt = System.currentTimeMillis())
        _currentConversation.value = updated
        writeConversation { conversationRepo.saveConversation(updated) }
    }

    fun startConversationSummary(request: ConversationSummaryRequest): Boolean {
        if (isGenerating.value || summaryJob?.isActive == true || pendingConversationWrites > 0) return false
        val conversation = _currentConversation.value ?: return false
        if (conversation.messages.isEmpty()) return false
        val configured = summaryTextModel() ?: return false
        val (provider, config) = configured
        summaryJob = viewModelScope.launch {
            try {
                summaries.summarize(conversation, provider, config, request)
            } finally {
                summaryJob = null
                drainQueuedMessages()
            }
        }
        return true
    }

    private fun generateConversationTitle(
        conversation: Conversation,
        expectedFallbackTitle: String
    ) {
        val configured = configuredTextModel("title_generation") ?: return
        val (provider, config) = configured
        viewModelScope.launch {
            val conversationId = conversation.id
            val titleMessages = conversation.messages
                .filter { it.role == ChatRole.USER || it.role == ChatRole.MODEL }
                .map { message ->
                    StoredMessage(
                        id = message.id,
                        role = message.role,
                        versions = listOf(MessageVersion(content = message.content))
                    )
                }
                .filter { it.content.isNotBlank() }
            val raw = runCatching {
                toolChat.completeText(
                    provider,
                    config.modelId,
                    titleMessages,
                    config.prompt.ifBlank { DEFAULT_TITLE_GENERATION_PROMPT }
                )
            }.getOrNull().orEmpty()
            val title = raw.lineSequence().firstOrNull().orEmpty()
                .trim().trim('"', '\'', '`').take(80)
            if (title.isBlank()) return@launch

            if (generation.snapshot?.id == conversationId) {
                generation.updateConversation { current ->
                    if (current.id == conversationId && current.title == expectedFallbackTitle) {
                        current.copy(title = title, updatedAt = System.currentTimeMillis())
                    } else {
                        current
                    }
                }
            } else {
                val current = _currentConversation.value?.takeIf { it.id == conversationId }
                    ?: conversationRepo.getById(conversationId)
                    ?: return@launch
                if (current.title != expectedFallbackTitle) return@launch
                val updated = current.copy(title = title, updatedAt = System.currentTimeMillis())
                conversationRepo.saveConversation(updated)
                if (_currentConversation.value?.id == conversationId) _currentConversation.value = updated
            }
        }
    }

    private fun generatedVersion(provider: LlmProviderInfo?, modelId: String, timestamp: String) = MessageVersion(
        timestamp = timestamp,
        providerId = provider?.id.orEmpty(),
        modelId = modelId,
        modelDisplayName = provider?.config?.modelConfigs?.get(modelId)?.displayName?.ifBlank { modelId } ?: modelId
    )

    fun sendMessage(userText: String, fileAttachments: List<String> = emptyList()): Boolean {
        if (userText.isBlank() && fileAttachments.isEmpty()) return false

        if (isGenerating.value || summaryJob?.isActive == true || pendingConversationWrites > 0) {
            val queued = StoredMessage(
                id = UUID.randomUUID().toString(),
                role = ChatRole.USER,
                versions = listOf(
                    MessageVersion(
                        content = userText,
                        timestamp = System.currentTimeMillis().toString(),
                        files = fileAttachments
                    )
                ),
                isQueued = true
            )
            _queuedMessages.value = _queuedMessages.value + queued
            return true
        }

        val incoming = StoredMessage(UUID.randomUUID().toString(), ChatRole.USER,
            listOf(MessageVersion(content = userText, files = fileAttachments, timestamp = System.currentTimeMillis().toString())),
            isQueued = true)
        if (maybeStartAutomaticSummary(incoming)) {
            _queuedMessages.value = _queuedMessages.value + incoming
            return true
        }
        val prefs = appPreferences.value
        val baseProvider = providers.value.find { it.id == prefs.selectedProviderId }
        val modelId = prefs.selectedModelId
        val canGenerate = baseProvider != null && modelId.isNotBlank()
        val now = System.currentTimeMillis()
        val user = StoredMessage(
            UUID.randomUUID().toString(),
            ChatRole.USER,
            listOf(
                MessageVersion(
                    content = userText,
                    timestamp = now.toString(),
                    files = fileAttachments
                )
            )
        )
        val assistant = StoredMessage(
            UUID.randomUUID().toString(),
            ChatRole.MODEL,
            listOf(generatedVersion(baseProvider, modelId, now.toString()))
        )
        val existing = _currentConversation.value
        val firstMessage = existing == null || existing.messages.isEmpty()
        val fallbackSource = userText.ifBlank { "Attachment" }
        val fallbackTitle = fallbackSource.take(30) + if (fallbackSource.length > 30) "..." else ""
        val conv = (existing ?: Conversation(
            id = UUID.randomUUID().toString(),
            title = fallbackTitle,
            createdAt = now,
            updatedAt = now
        )).copy(
            title = if (firstMessage) fallbackTitle else existing?.title ?: fallbackTitle,
            messages = (existing?.messages ?: emptyList()) + user +
                if (canGenerate) listOf(assistant) else emptyList(),
            updatedAt = now,
            providerId = baseProvider?.id.orEmpty(),
            modelId = modelId,
            profileId = null
        )
        _currentConversation.value = conv

        if (!canGenerate) {
            writeConversation { conversationRepo.saveConversation(conv) }
            return true
        }

        val provider = providerWithReasoning(requireNotNull(baseProvider), modelId, conv)
        val context = effectiveContext(
            conv,
            conv.messages.dropLast(1),
            prefs.effectiveSystemPrompt
        ).let { it.copy(messages = prefs.promptRoleMessages() + it.messages) }
        val started = generation.startEvents(
            conv,
            assistant.id,
            toolEvents(provider, modelId, context.messages, context.systemPrompt)
        )
        if (started && !conv.startedWithDirectMedia() && shouldGenerateConversationTitle(
                userText,
                conv.messages.count { it.role == ChatRole.USER || it.role == ChatRole.MODEL }
            )) {
            val originalUserText = conv.messages.firstOrNull { it.role == ChatRole.USER }?.content
                .orEmpty().ifBlank { "Attachment" }
            val expectedFallbackTitle = originalUserText.take(30) +
                if (originalUserText.length > 30) "..." else ""
            generateConversationTitle(conv, expectedFallbackTitle)
        }
        return started
    }

    fun sendMedia(request: DirectMediaRequest): Boolean {
        if (isGenerating.value || summaryJob?.isActive == true || pendingConversationWrites > 0 ||
            request.prompt.isBlank() || request.prompt.length > 32000) return false
        if (request.kind !in listOf(ModelType.IMAGE_GENERATION, ModelType.VIDEO_GENERATION)) return false
        val provider = providers.value.find { it.id == request.providerId } ?: return false
        if (provider.config.modelConfigs[request.modelId]?.modelType != request.kind ||
            provider.config.modelIds?.contains(request.modelId) == false) return false
        val now = System.currentTimeMillis()
        val user = StoredMessage(UUID.randomUUID().toString(), ChatRole.USER,
            listOf(MessageVersion(content = request.prompt, timestamp = now.toString(), files = request.attachments)))
        val assistant = StoredMessage(UUID.randomUUID().toString(), ChatRole.MODEL,
            listOf(generatedVersion(provider, request.modelId, now.toString())))
        val existing = _currentConversation.value
        val isFirstMessage = existing == null || existing.messages.isEmpty()
        val conversation = (existing ?: Conversation(
            id = UUID.randomUUID().toString(), title = request.prompt.take(30),
            createdAt = now, updatedAt = now
        )).copy(
            title = if (isFirstMessage) request.prompt.take(30) else existing?.title ?: request.prompt.take(30),
            messages = existing?.messages.orEmpty() + user + assistant,
            updatedAt = now
        )
        val name = if (request.kind == ModelType.IMAGE_GENERATION) "generate_image" else "generate_video"
        val config = toolSettings.value.system[name]?.takeIf {
            it.providerId == provider.id && it.modelId == request.modelId
        }
        _currentConversation.value = conversation
        return generation.startEvents(conversation, assistant.id,
            toolChat.generateMedia(provider, request.modelId, request.kind, request.prompt,
                config?.imageOptions ?: obj(), request.attachments,
                videoOptions = config?.videoOptions ?: obj()))
    }

    fun stopGeneration() { generation.stop() }

    fun editMessage(messageId: String, newContent: String, files: List<String>): Boolean {
        if (
            isGenerating.value ||
            summaryJob?.isActive == true ||
            (newContent.isBlank() && files.isEmpty())
        ) return false
        val conv = _currentConversation.value ?: return false
        val currentMsgs = conv.messages.toMutableList()
        val idx = currentMsgs.indexOfFirst { it.id == messageId }
        if (idx == -1) return false

        val oldMsg = currentMsgs[idx]
        val newVersions = oldMsg.versions.toMutableList()
        newVersions.add(
            MessageVersion(
                content = newContent,
                timestamp = System.currentTimeMillis().toString(),
                files = files,
                providerId = oldMsg.activeVersion.providerId,
                modelId = oldMsg.activeVersion.modelId,
                modelDisplayName = oldMsg.activeVersion.modelDisplayName
            )
        )
        currentMsgs[idx] = oldMsg.copy(
            versions = newVersions,
            activeVersionIndex = newVersions.size - 1
        )
        val updated = conv.copy(
            messages = currentMsgs,
            summary = summaryAfterMessageMutation(conv, idx, currentMsgs),
            updatedAt = System.currentTimeMillis()
        )
        _currentConversation.value = updated
        saveEditedConversation(conv, updated)
        return true
    }

    fun deleteMessage(messageId: String) {
        if (isGenerating.value) return
        val conv = _currentConversation.value ?: return
        val messageIndex = conv.messages.indexOfFirst { it.id == messageId }
        if (messageIndex == -1) return
        val currentMsgs = conv.messages.filter { it.id != messageId }
        val updated = conv.copy(
            messages = currentMsgs,
            summary = summaryAfterMessageMutation(conv, messageIndex, currentMsgs),
            updatedAt = System.currentTimeMillis()
        )
        _currentConversation.value = updated
        saveEditedConversation(conv, updated)
    }

    fun deleteMessageVersion(messageId: String) {
        if (isGenerating.value) return
        val conv = _currentConversation.value ?: return
        val currentMsgs = conv.messages.toMutableList()
        val messageIndex = currentMsgs.indexOfFirst { it.id == messageId }
        if (messageIndex == -1) return

        val message = currentMsgs[messageIndex]
        if (message.versions.size <= 1) {
            currentMsgs.removeAt(messageIndex)
        } else {
            val versions = message.versions.toMutableList().apply {
                removeAt(message.activeVersionIndex.coerceIn(indices))
            }
            currentMsgs[messageIndex] = message.copy(
                versions = versions,
                activeVersionIndex = message.activeVersionIndex.coerceAtMost(versions.lastIndex)
            )
        }

        val updated = conv.copy(
            messages = currentMsgs,
            summary = summaryAfterMessageMutation(conv, messageIndex, currentMsgs),
            updatedAt = System.currentTimeMillis()
        )
        _currentConversation.value = updated
        saveEditedConversation(conv, updated)
    }

    fun switchMessageVersion(messageId: String, versionIndex: Int) {
        if (isGenerating.value) return
        val conv = _currentConversation.value ?: return
        val currentMsgs = conv.messages.toMutableList()
        val idx = currentMsgs.indexOfFirst { it.id == messageId }
        if (idx != -1) {
            val oldMsg = currentMsgs[idx]
            if (versionIndex in oldMsg.versions.indices) {
                currentMsgs[idx] = oldMsg.copy(activeVersionIndex = versionIndex)
                val updated = conv.copy(
                    messages = currentMsgs,
                    summary = summaryAfterMessageMutation(conv, idx, currentMsgs),
                    updatedAt = System.currentTimeMillis()
                )
                _currentConversation.value = updated
                saveEditedConversation(conv, updated)
            }
        }
    }

    fun resendUserMessage(messageId: String): Boolean {
        if (isGenerating.value || summaryJob?.isActive == true || pendingConversationWrites > 0) return false
        val current = _currentConversation.value ?: return false
        val prefs = appPreferences.value
        val baseProvider = providers.value.find { it.id == prefs.selectedProviderId } ?: return false
        val model = prefs.selectedModelId.takeIf { it.isNotBlank() } ?: return false
        val responseId = UUID.randomUUID().toString()
        val conv = prepareUserMessageRetry(current, messageId, responseId,
            generatedVersion(baseProvider, model, System.currentTimeMillis().toString()))?.copy(
            providerId = baseProvider.id,
            modelId = model,
            profileId = null
        ) ?: return false
        val provider = providerWithReasoning(baseProvider, model, conv)
        val contextMessages = conv.messages.takeWhile { it.id != responseId }
        val context = effectiveContext(conv, contextMessages.dropLast(1), prefs.effectiveSystemPrompt)
            .let { it.copy(messages = prefs.promptRoleMessages() + it.messages + contextMessages.last()) }
        return generation.startEvents(conv, responseId,
            toolEvents(provider, model, context.messages, context.systemPrompt))
    }

    fun regenerateMessage(messageId: String) {
        if (isGenerating.value || summaryJob?.isActive == true || pendingConversationWrites > 0) return
        val current = _currentConversation.value ?: return
        val prefs = appPreferences.value
        val baseProvider = providers.value.find { it.id == prefs.selectedProviderId } ?: return
        val model = prefs.selectedModelId.takeIf { it.isNotBlank() } ?: return
        var conv = prepareRegeneration(current, messageId,
            generatedVersion(baseProvider, model, System.currentTimeMillis().toString()))?.copy(
            providerId = baseProvider.id,
            modelId = model,
            profileId = null
        ) ?: return

        val summaryBoundary = current.summary?.throughMessageId
        if (summaryBoundary != null && conv.messages.none { it.id == summaryBoundary }) {
            conv = conv.copy(summary = null)
        }
        val provider = providerWithReasoning(baseProvider, model, conv)
        val context = effectiveContext(
            conv,
            conv.messages.dropLast(1),
            prefs.effectiveSystemPrompt
        ).let { it.copy(messages = prefs.promptRoleMessages() + it.messages) }
        generation.startEvents(
            conv,
            messageId,
            toolEvents(provider, model, context.messages, context.systemPrompt)
        )
    }

    override fun onCleared() {
        super.onCleared()
        speech.shutdown()
    }
}
