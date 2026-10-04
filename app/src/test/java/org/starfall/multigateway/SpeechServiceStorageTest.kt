package org.starfall.multigateway

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.starfall.multigateway.data.local.db.AppDatabase
import org.starfall.multigateway.data.local.db.entities.SpeechServiceEntity
import org.starfall.multigateway.data.model.SpeechService
import org.starfall.multigateway.data.repository.SpeechRepository

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SpeechServiceStorageTest {
    @Test fun speechOptionsSurviveRepositoryReloadAndReordering() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            val service = SpeechService("s", "Voice", provider = "p", modelId = "tts", voice = "Kore",
                instructions = "Speak calmly", responseFormat = "wav", languageCode = "vi-VN",
                extraBody = buildJsonObject { put("custom", "value") })
            SpeechRepository(db).saveService(service)
            assertEquals(service, SpeechRepository(db).getById("s"))
            SpeechRepository(db).reorderServices(listOf("s"))
            assertEquals(service.copy(sortOrder = 0), SpeechRepository(db).getById("s"))
        } finally { db.close() }
    }

    @Test fun legacyRowsWithoutOptionsKeepDefaults() = runBlocking {
        installTestAndroidKeyStore()
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java).build()
        try {
            db.speechServiceDao().insertOrUpdate(SpeechServiceEntity("s", "Old", "system", null, "Default", 1f, 1f, "", 0))
            val service = SpeechRepository(db).getById("s")!!
            assertEquals("mp3", service.responseFormat)
            assertEquals("", service.instructions)
            assertTrue(service.extraBody.isEmpty())
        } finally { db.close() }
    }
}
