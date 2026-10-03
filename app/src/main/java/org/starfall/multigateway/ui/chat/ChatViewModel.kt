package org.starfall.multigateway.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.starfall.multigateway.data.local.preferences.AppPreferences
import org.starfall.multigateway.data.local.preferences.AppPreferencesRepository
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.repository.*
import org.starfall.multigateway.data.service.TtsHelper
import org.starfall.multigateway.data.service.SpeechAudioPlayer
import org.starfall.multigateway.data.service.SpeechSynthesisService
import java.util.UUID
import org.starfall.multigateway.data.tools.*

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
    fun setSystemTool(name: String, config: SystemToolConfig) { viewModelScope.launch { toolStore.update { it.copy(system = it.system + (name to config)) } } }
    fun setQuickMcp(id: String, enabled: Boolean) { viewModelScope.launch { toolStore.update { it.copy(quickMcp = it.quickMcp + (id to enabled)) } } }
    fun setMcpToolEnabled(serverId: String, toolName: String, enabled: Boolean) {
        viewModelScope.launch {
            toolStore.update { settings ->
                val serverTools = settings.mcpTools[serverId].orEmpty() + (toolName to enabled)
                settings.copy(mcpTools = settings.mcpTools + (serverId to serverTools))
            }
        }
    }
    private fun toolEvents(provider: LlmProviderInfo, model: String, messages: List<StoredMessage>, prompt: String) =
        toolChat.generate(provider, model, messages, prompt, mcpServers.value, providers.value,
            settings = { toolSettings.value })

    val conversations: StateFlow<List<Conversation>> = conversationRepo.allConversations
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

    private val _summaryProgress = MutableStateFlow<ConversationSummaryProgress?>(null)
    val summaryProgress: StateFlow<ConversationSummaryProgress?> = _summaryProgress.asStateFlow()
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
                    (modelId to config.withConversationReasoning(effort))
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
        if (_currentConversation.value?.id == updated.id) _currentConversation.value = updated
    }
    val isGenerating = generation.busy

    val contextWindowStatus = combine(_currentConversation, appPreferences, providers) { conversation, prefs, available ->
        val config = available.find { it.id == prefs.selectedProviderId }?.config?.modelConfigs?.get(prefs.selectedModelId)
        if (conversation == null || config == null || config.modelType != ModelType.TEXT_GENERATION) null
        else contextWindowStatus(conversation, prefs.defaultSystemPrompt, config)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

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
        val status = contextWindowStatus(current, prefs.defaultSystemPrompt, model)
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
        viewModelScope.launch { contextWindowStatus.collect { maybeStartAutomaticSummary() } }
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

    private fun writeConversation(block: suspend () -> Unit) {
        pendingConversationWrites++
        viewModelScope.launch {
            try { block() } finally {
                pendingConversationWrites--
                drainQueuedMessages()
            }
        }
    }

    fun selectConversation(conversation: Conversation) {
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

    fun deleteConversation(id: String) {
        writeConversation {
            if (generation.snapshot?.id == id) generation.stopAndJoin()
            conversationRepo.deleteConversation(id)
            prefsRepo.updateSidebar { it.removeChats(setOf(id)) }
            if (_currentConversation.value?.id == id) {
                _currentConversation.value = null
                _queuedMessages.value = emptyList()
            }
        }
    }

    fun deleteConversations(ids: Set<String>) {
        writeConversation {
            if (generation.snapshot?.id in ids) generation.stopAndJoin()
            conversationRepo.deleteConversations(ids.toList())
            prefsRepo.updateSidebar { it.removeChats(ids) }
            if (_currentConversation.value?.id in ids) {
                _currentConversation.value = null
                _queuedMessages.value = emptyList()
            }
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        writeConversation {
            if (generation.snapshot?.id == id) generation.stopAndJoin()
            val conv = conversationRepo.getById(id) ?: return@writeConversation
            val updated = conv.copy(title = newTitle, updatedAt = System.currentTimeMillis())
            conversationRepo.saveConversation(updated)
            if (_currentConversation.value?.id == id) {
                _currentConversation.value = updated
            }
        }
    }

    fun clearAllConversations() {
        writeConversation {
            generation.stopAndJoin()
            conversationRepo.deleteAll()
            _queuedMessages.value = emptyList()
            prefsRepo.updateSidebar { org.starfall.multigateway.data.model.SidebarOrganization() }
            _currentConversation.value = null
        }
    }

    fun deleteAllUserData() {
        writeConversation {
            generation.stopAndJoin()
            conversationRepo.deleteAll()
            _queuedMessages.value = emptyList()
            prefsRepo.updateSidebar { org.starfall.multigateway.data.model.SidebarOrganization() }
            _currentConversation.value = null
            // Also reset active profile to default
            prefsRepo.setSelectedProfileId(null)
        }
    }

    fun cleanCache() {
        // Clear cached responses and stopped streams
        stopGeneration()
        stopSpeaking()
    }


    fun setDefaultSystemPrompt(prompt: String) {
        viewModelScope.launch {
            prefsRepo.setDefaultSystemPrompt(prompt)
        }
    }

    fun selectModel(providerId: String, modelId: String) {
        viewModelScope.launch {
            val provider = llmRepo.getProviderById(providerId)
            if (provider?.config?.modelIds != null && modelId !in provider.config.modelIds) {
                llmRepo.saveProvider(provider.copy(config = provider.config.copy(
                    modelIds = provider.config.modelIds + modelId,
                    modelConfigs = provider.config.modelConfigs + (modelId to ModelConfiguration())
                )))
            }
            prefsRepo.setSelectedModel(providerId, modelId)
        }
    }

    fun selectSpeechService(serviceId: String?) {
        viewModelScope.launch { prefsRepo.setSelectedSpeechServiceId(serviceId) }
    }

    private fun speakWithService(service: SpeechService, text: String) {
        if (text.isBlank()) return
        if (service.provider.equals("system", ignoreCase = true)) {
            speechAudioPlayer.stop()
            ttsHelper.speak(text, service.speed, service.pitch, service.voice)
            return
        }

        viewModelScope.launch {
            val provider = providers.value.find { it.id == service.provider }
                ?: return@launch
            val modelId = service.modelId ?: return@launch
            val modelConfig = provider.config.modelConfigs[modelId] ?: return@launch
            if (modelConfig.modelType != ModelType.TEXT_TO_SPEECH) return@launch
            ttsHelper.stop()
            runCatching { speechSynthesis.synthesize(provider, service, text) }
                .onSuccess { speechAudioPlayer.play(it) }
        }
    }

    fun speakText(text: String) {
        val services = speechServices.value
        val selectedId = appPreferences.value.selectedSpeechServiceId
        val service = selectedId?.let { id -> services.find { it.id == id } }
            ?: services.find { it.provider.equals("system", ignoreCase = true) }
            ?: services.firstOrNull()
            ?: return
        speakWithService(service, text)
    }

    fun testVoice(service: SpeechService, text: String) = speakWithService(service, text)

    fun stopSpeaking() {
        ttsHelper.stop()
        speechAudioPlayer.stop()
    }

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

    private fun estimateTokens(message: StoredMessage): Int =
        estimateMessageTokens(message, includeReasoning = true).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun chunkMessages(messages: List<StoredMessage>, tokenLimit: Int): List<List<StoredMessage>> =
        chunkSummaryMessages(messages, tokenLimit)

    fun startConversationSummary(request: ConversationSummaryRequest): Boolean {
        if (isGenerating.value || summaryJob?.isActive == true || pendingConversationWrites > 0) return false
        val conversation = _currentConversation.value ?: return false
        if (conversation.messages.isEmpty()) return false
        val configured = summaryTextModel() ?: return false
        val (provider, config) = configured
        val targetTokens = request.targetTokens.coerceAtLeast(1)
        val cutoff = conversation.messages.last().id
        val basePrompt = config.prompt.ifBlank { DEFAULT_CHAT_SUMMARY_PROMPT } +
            "\n\nTarget summary length: approximately $targetTokens tokens. Return only the summary."
        val effective = effectiveContext(conversation, conversation.messages, basePrompt)
        val modelId = config.modelId

        summaryJob = viewModelScope.launch {
            _summaryProgress.value = ConversationSummaryProgress(
                conversation.id, cutoff, "Preparing summary…", 0f
            )
            try {
                val text = if (!request.chunked) {
                    _summaryProgress.value = ConversationSummaryProgress(
                        conversation.id, cutoff, "Summarizing conversation…", 0.35f
                    )
                    toolChat.completeText(
                        provider,
                        modelId,
                        effective.messages,
                        effective.systemPrompt,
                        maxOutputTokens = targetTokens
                    )
                } else {
                    val chunks = chunkMessages(
                        effective.messages,
                        request.tokensPerChunk.coerceAtLeast(1)
                    )
                    val partials = mutableListOf<String>()
                    chunks.forEachIndexed { index, chunk ->
                        _summaryProgress.value = ConversationSummaryProgress(
                            conversation.id,
                            cutoff,
                            "Summarizing part ${index + 1}/${chunks.size}…",
                            ((index + 1).toFloat() / (chunks.size + 1).coerceAtLeast(1))
                        )
                        partials += toolChat.completeText(
                            provider,
                            modelId,
                            chunk,
                            config.prompt.ifBlank { DEFAULT_CHAT_SUMMARY_PROMPT } +
                                "\n\nThis is part ${index + 1} of ${chunks.size}. Produce a compact partial summary for later merging.",
                            maxOutputTokens = targetTokens
                        ).trim()
                    }
                    _summaryProgress.value = ConversationSummaryProgress(
                        conversation.id, cutoff, "Merging summaries…", 0.9f
                    )
                    val helperWindow = provider.config.modelConfigs[modelId]?.contextWindowTokens?.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW_TOKENS
                    val mergeBudget = (helperWindow * 0.6).toInt().coerceAtLeast(16)
                    var merging = partials.toList()
                    while (merging.sumOf { estimateTextTokens(it) + 4 } > mergeBudget && merging.size > 1) {
                        val batches = chunkMessages(merging.mapIndexed { index, part ->
                            StoredMessage("merge_$index", ChatRole.USER, listOf(MessageVersion(content = part)))
                        }, mergeBudget)
                        val reduced = batches.map { batch ->
                            toolChat.completeText(provider, modelId, batch, basePrompt, maxOutputTokens = targetTokens).trim()
                        }
                        check(reduced.sumOf { estimateTextTokens(it) } < merging.sumOf { estimateTextTokens(it) }) {
                            "Summary could not be compressed to fit the summary model's context window"
                        }
                        merging = reduced
                    }
                    val mergeMessages = merging.mapIndexed { index, part ->
                        StoredMessage(
                            id = "summary_part_$index",
                            role = ChatRole.USER,
                            versions = listOf(MessageVersion(content = "Part ${index + 1}:\n$part"))
                        )
                    }
                    toolChat.completeText(
                        provider,
                        modelId,
                        mergeMessages,
                        basePrompt,
                        maxOutputTokens = targetTokens
                    )
                }.trim()

                check(text.isNotBlank()) { "Summary returned no content" }
                if (text.isNotBlank()) {
                    val latest = conversationRepo.getById(conversation.id)
                        ?: _currentConversation.value?.takeIf { it.id == conversation.id }
                        ?: conversation
                    val updated = latest.copy(
                        summary = ConversationSummary(
                            id = UUID.randomUUID().toString(),
                            content = text,
                            throughMessageId = cutoff,
                            role = SummaryRole.SYSTEM
                        ),
                        updatedAt = System.currentTimeMillis()
                    )
                    conversationRepo.saveConversation(updated)
                    if (_currentConversation.value?.id == updated.id) _currentConversation.value = updated
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _summaryProgress.value = ConversationSummaryProgress(
                    conversation.id,
                    cutoff,
                    "Summary failed: ${e.localizedMessage ?: "Unknown error"}",
                    0f
                )
                delay(2500)
            } finally {
                _summaryProgress.value = null
                summaryJob = null
                drainQueuedMessages()
            }
        }
        return true
    }

    private fun generateConversationTitle(
        conversationId: String,
        firstUserText: String,
        expectedFallbackTitle: String
    ) {
        val configured = configuredTextModel("title_generation") ?: return
        val (provider, config) = configured
        viewModelScope.launch {
            val message = StoredMessage(
                id = "title_source",
                role = ChatRole.USER,
                versions = listOf(MessageVersion(content = firstUserText))
            )
            val raw = runCatching {
                toolChat.completeText(
                    provider,
                    config.modelId,
                    listOf(message),
                    config.prompt.ifBlank { DEFAULT_TITLE_GENERATION_PROMPT }
                )
            }.getOrNull().orEmpty()
            val title = raw.lineSequence().firstOrNull().orEmpty()
                .trim().trim('"', '\'', '`').take(80)
            if (title.isBlank()) return@launch

            if (generation.snapshot?.id == conversationId) {
                generation.updateConversation { current ->
                    if (current.id == conversationId && current.title == expectedFallbackTitle) {
                        current.copy(title = title)
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
            listOf(MessageVersion(timestamp = now.toString()))
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
            prefs.defaultSystemPrompt
        )
        val started = generation.startEvents(
            conv,
            assistant.id,
            toolEvents(provider, modelId, context.messages, context.systemPrompt)
        )
        if (started && firstMessage) {
            generateConversationTitle(
                conv.id,
                userText.ifBlank { "Conversation started with ${fileAttachments.size} attachment(s)." },
                fallbackTitle
            )
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
            listOf(MessageVersion(content = request.prompt, timestamp = now.toString())))
        val assistant = StoredMessage(UUID.randomUUID().toString(), ChatRole.MODEL,
            listOf(MessageVersion(timestamp = now.toString())))
        val existing = _currentConversation.value
        val conversation = (existing ?: Conversation(
            id = UUID.randomUUID().toString(), title = request.prompt.take(30),
            createdAt = now, updatedAt = now
        )).copy(messages = existing?.messages.orEmpty() + user + assistant, updatedAt = now)
        val name = if (request.kind == ModelType.IMAGE_GENERATION) "generate_image" else "generate_video"
        val config = toolSettings.value.system[name]?.takeIf {
            it.providerId == provider.id && it.modelId == request.modelId
        }
        _currentConversation.value = conversation
        return generation.startEvents(conversation, assistant.id,
            toolChat.generateMedia(provider, request.modelId, request.kind, request.prompt,
                config?.imageOptions ?: obj()))
    }

    fun stopGeneration() { generation.stop() }

    fun editMessage(messageId: String, newContent: String, files: List<String>): Boolean {
        if (
            isGenerating.value ||
            summaryJob?.isActive == true ||
            pendingConversationWrites > 0 ||
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
                files = files
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
        writeConversation { conversationRepo.saveConversation(updated) }
        return true
    }

    fun deleteMessage(messageId: String) {
        if (isGenerating.value || pendingConversationWrites > 0) return
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
        writeConversation { conversationRepo.saveConversation(updated) }
    }

    fun deleteMessageVersion(messageId: String) {
        if (isGenerating.value || pendingConversationWrites > 0) return
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
        writeConversation { conversationRepo.saveConversation(updated) }
    }

    fun switchMessageVersion(messageId: String, versionIndex: Int) {
        if (isGenerating.value || pendingConversationWrites > 0) return
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
                writeConversation { conversationRepo.saveConversation(updated) }
            }
        }
    }

    fun regenerateMessage(messageId: String) {
        if (isGenerating.value || summaryJob?.isActive == true || pendingConversationWrites > 0) return
        val current = _currentConversation.value ?: return
        val prefs = appPreferences.value
        val baseProvider = providers.value.find { it.id == prefs.selectedProviderId } ?: return
        val model = prefs.selectedModelId.takeIf { it.isNotBlank() } ?: return
        var conv = prepareRegeneration(current, messageId)?.copy(
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
            prefs.defaultSystemPrompt
        )
        generation.startEvents(
            conv,
            messageId,
            toolEvents(provider, model, context.messages, context.systemPrompt)
        )
    }

    override fun onCleared() {
        super.onCleared()
        ttsHelper.shutdown()
        speechAudioPlayer.shutdown()
    }
}
