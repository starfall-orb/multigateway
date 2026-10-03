package org.starfall.multigateway.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.starfall.multigateway.data.local.db.dao.*
import org.starfall.multigateway.data.local.db.entities.*

@Database(
    entities = [
        ConversationEntity::class,
        ChatProfileEntity::class,
        ProviderGroupEntity::class,
        LlmProviderEntity::class,
        LlmModelsEntity::class,
        McpServerEntity::class,
        SpeechServiceEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun chatProfileDao(): ChatProfileDao
    abstract fun providerGroupDao(): ProviderGroupDao
    abstract fun llmProviderDao(): LlmProviderDao
    abstract fun llmModelsDao(): LlmModelsDao
    abstract fun mcpServerDao(): McpServerDao
    abstract fun speechServiceDao(): SpeechServiceDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS speech_services (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, provider TEXT NOT NULL, voice TEXT NOT NULL, speed REAL NOT NULL, pitch REAL NOT NULL, apiKey TEXT NOT NULL)")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE speech_services ADD COLUMN modelId TEXT")
                db.execSQL("ALTER TABLE mcp_servers ADD COLUMN cachedToolsJson TEXT")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                listOf("chat_profiles", "llm_providers", "mcp_servers", "speech_services").forEach { table ->
                    db.execSQL("ALTER TABLE $table ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0")
                    db.execSQL("UPDATE $table SET sortOrder = rowid")
                }
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN summaryJson TEXT")
                db.execSQL("ALTER TABLE conversations ADD COLUMN reasoningEffort TEXT")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS provider_groups (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, sortOrder INTEGER NOT NULL)")
                db.execSQL("ALTER TABLE llm_providers ADD COLUMN groupId TEXT")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE provider_groups ADD COLUMN icon TEXT")
            }
        }

        // No schema change: upgrade existing v2 secrets atomically before any DAO reads.
        internal fun encryptLegacySecrets(db: SupportSQLiteDatabase) {
            db.query("PRAGMA secure_delete = ON").close()
            db.beginTransaction()
            try {
                val columns = mapOf("llm_providers" to listOf("authJson", "configJson", "baseUrl"),
                    "mcp_servers" to listOf("headersJson", "url"), "speech_services" to listOf("apiKey"))
                columns.forEach { (table, fields) -> fields.forEach { field ->
                    db.query("SELECT id, $field FROM $table WHERE $field IS NOT NULL").use { rows ->
                        while (rows.moveToNext()) {
                            val value = rows.getString(1)
                            if (!SecretCipher.isEncrypted(value)) db.execSQL("UPDATE $table SET $field = ? WHERE id = ?",
                                arrayOf(SecretCipher.encrypt(value), rows.getString(0)))
                        }
                    }
                } }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "multigateway_db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7).addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) { encryptLegacySecrets(db) }
                }).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
