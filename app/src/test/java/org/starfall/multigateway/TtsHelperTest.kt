package org.starfall.multigateway

import android.content.Context
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import org.starfall.multigateway.data.service.TtsHelper

@Config(sdk = [28])
@RunWith(RobolectricTestRunner::class)
class TtsHelperTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private fun idle() = Shadows.shadowOf(Looper.getMainLooper()).idle()

    @Test fun requestBeforeInitializationIsPlayedWhenEngineIsReady() {
        val helper = TtsHelper(context)
        helper.speak("First request")
        idle()
        val engine = Shadows.shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        assertNull(engine.lastSpokenText)
        engine.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertEquals("First request", engine.lastSpokenText)
        helper.shutdown(); idle()
    }

    @Test fun stopDuringInitializationCancelsPendingSpeech() {
        val helper = TtsHelper(context)
        helper.speak("Must not be played")
        idle()
        val engine = Shadows.shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        helper.stop(); idle()
        engine.onInitListener.onInit(TextToSpeech.SUCCESS)
        idle()
        assertNull(engine.lastSpokenText)
        helper.shutdown(); idle()
    }

    @Test fun longMessagesAreQueuedWithinAndroidInputLimit() {
        val helper = TtsHelper(context)
        val text = "a".repeat(TextToSpeech.getMaxSpeechInputLength() + 20)
        helper.speak(text); idle()
        val engine = Shadows.shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
        engine.onInitListener.onInit(TextToSpeech.SUCCESS); idle()
        assertEquals(text, engine.spokenTextList.joinToString(""))
        assertTrue(engine.spokenTextList.all { it.length <= TextToSpeech.getMaxSpeechInputLength() })
        assertEquals(TextToSpeech.QUEUE_ADD, engine.queueMode)
        helper.shutdown(); idle()
    }
}
