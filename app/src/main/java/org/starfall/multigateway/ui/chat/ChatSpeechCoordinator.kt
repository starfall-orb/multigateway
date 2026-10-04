package org.starfall.multigateway.ui.chat

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.starfall.multigateway.data.local.preferences.AppPreferences
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.*

internal class ChatSpeechCoordinator(
    private val scope: CoroutineScope,
    private val preferences: StateFlow<AppPreferences>,
    private val providers: StateFlow<List<LlmProviderInfo>>,
    private val services: StateFlow<List<SpeechService>>,
    private val tts: TtsHelper,
    private val synthesis: SpeechSynthesisService,
    private val player: SpeechAudioPlayer
) {
    private var job: Job? = null
    private var requestNumber = 0L
    private val activeMessage = MutableStateFlow<String?>(null)
    val speakingMessageId = activeMessage.asStateFlow()
    private val activeTestService = MutableStateFlow<String?>(null)
    val activeTestServiceId = activeTestService.asStateFlow()

    fun speakWithService(service: SpeechService, text: String, messageId: String? = null, isTest: Boolean = false) {
        val plainText = speechText(text, includeCodeBlocks = preferences.value.ttsReadCodeBlocks)
        if (plainText.isBlank()) return
        stop()
        val request = requestNumber
        activeMessage.value = messageId
        if (isTest) activeTestService.value = service.id
        val onFinished = {
            if (request == requestNumber) {
                activeMessage.value = null
                activeTestService.value = null
            }
        }
        if (service.provider.equals("system", ignoreCase = true)) {
            tts.speak(plainText, service.speed, service.pitch, service.voice, onFinished)
            return
        }
        job = scope.launch {
            try {
                val provider = providers.value.find { it.id == service.provider }
                    ?: error("Speech provider no longer exists.")
                val modelId = service.modelId ?: error("Select a TTS model first.")
                require(provider.config.modelConfigs[modelId]?.modelType == ModelType.TEXT_TO_SPEECH &&
                    provider.config.modelIds?.contains(modelId) != false) { "The selected TTS model is unavailable." }
                val audio = synthesis.synthesize(provider, service, plainText)
                ensureActive()
                player.play(audio, onFinished, onError = { tts.reportError("Could not play synthesized speech.") })
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                tts.reportError(e.localizedMessage ?: "Speech synthesis failed.")
                onFinished()
            }
        }
    }

    fun speak(text: String, messageId: String? = null) {
        if (messageId != null && activeMessage.value == messageId) { stop(); return }
        val available = services.value
        val selectedId = preferences.value.selectedSpeechServiceId
        val service = selectedId?.let { id -> available.find { it.id == id } }
            ?: available.find { it.provider.equals("system", ignoreCase = true) }
            ?: available.firstOrNull() ?: return
        speakWithService(service, text, messageId)
    }

    fun stop() {
        requestNumber++
        activeMessage.value = null
        activeTestService.value = null
        job?.cancel()
        job = null
        tts.stop()
        player.stop()
    }

    fun shutdown() {
        stop()
        tts.shutdown()
        player.shutdown()
    }
}
