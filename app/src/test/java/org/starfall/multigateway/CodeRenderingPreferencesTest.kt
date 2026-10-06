package org.starfall.multigateway

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.multigateway.data.local.preferences.AppPreferencesRepository
import org.starfall.multigateway.data.local.preferences.WordWrapMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CodeRenderingPreferencesTest {
    @Test fun renderingPreferencesSurviveReopeningAndInvalidColumnDoesNotOverwriteThem() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repository = AppPreferencesRepository(context)
        repository.setWordWrapMode(WordWrapMode.BOUNDED)
        repository.setWordWrapColumn(37)
        repository.setCodePreviewEnabled(false)
        val restored = AppPreferencesRepository(context).appPreferencesFlow.first()
        assertEquals(WordWrapMode.BOUNDED, restored.wordWrapMode)
        assertEquals(37, restored.wordWrapColumn)
        assertFalse(restored.codePreviewEnabled)
        try {
            repository.setWordWrapColumn(0)
            fail("Zero columns must be rejected")
        } catch (_: IllegalArgumentException) { }
        assertEquals(37, AppPreferencesRepository(context).appPreferencesFlow.first().wordWrapColumn)
    }
}
