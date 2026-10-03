package org.starfall.multigateway.data.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import org.starfall.multigateway.R
import java.util.Locale

/** All engine access is serialized on the main thread, including asynchronous initialization. */
class TtsHelper(context: Context) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private data class Request(val text: String, val speed: Float, val pitch: Float, val voice: String)
    private var pending: Request? = null

    fun speak(text: String, speed: Float = 1.0f, pitch: Float = 1.0f, voice: String = "Default") {
        handler.post {
            if (closed || text.isBlank()) return@post
            pending = Request(text, speed, pitch, voice)
            if (ready) playPending() else if (tts == null) {
                tts = TextToSpeech(appContext) { status ->
                    // Posting also handles engines that invoke their listener inside the constructor.
                    handler.post { initialized(status) }
                }
            }
        }
    }

    private fun initialized(status: Int) {
        if (closed) return
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            pending = null
            engine.shutdown()
            tts = null
            notifyError()
            return
        }
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = Unit
            override fun onError(utteranceId: String?) { handler.post { notifyError() } }
        })
        // Retain the engine's installed voice if the app/device locale has no speech data.
        if (engine.isLanguageAvailable(Locale.getDefault()) >= TextToSpeech.LANG_AVAILABLE) {
            engine.language = Locale.getDefault()
        }
        ready = true
        playPending()
    }

    private fun playPending() {
        val request = pending ?: return
        val engine = tts ?: return
        pending = null
        engine.setSpeechRate(request.speed.coerceIn(0.1f, 4f))
        engine.setPitch(request.pitch.coerceIn(0.1f, 4f))
        if (!request.voice.equals("Default", true)) {
            engine.voices?.firstOrNull { it.name == request.voice }?.let { engine.voice = it }
        }
        // Android rejects a single utterance larger than its input limit.
        request.text.chunked(TextToSpeech.getMaxSpeechInputLength()).forEachIndexed { index, part ->
            val result = engine.speak(part, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                null, "MultiGatewayTTS-$index")
            if (result == TextToSpeech.ERROR) {
                engine.stop()
                notifyError()
                return
            }
        }
    }

    private fun notifyError() {
        Toast.makeText(appContext, R.string.tts_unavailable, Toast.LENGTH_LONG).show()
    }

    fun stop() {
        handler.post { pending = null; tts?.stop() }
    }

    fun shutdown() {
        handler.post {
            closed = true
            pending = null
            ready = false
            tts?.stop()
            tts?.shutdown()
            tts = null
        }
    }
}
