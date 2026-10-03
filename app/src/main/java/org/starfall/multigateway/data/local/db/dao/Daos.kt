package org.starfall.multigateway.data.local.db.dao

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import org.starfall.multigateway.data.local.db.entities.*

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface ChatProfileDao {
    @Query("SELECT * FROM chat_profiles ORDER BY sortOrder ASC, id ASC")
    fun getAllProfiles(): Flow<List<ChatProfileEntity>>

    @Query("SELECT * FROM chat_profiles WHERE id = :id")
    suspend fun getProfileById(id: String): ChatProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(profile: ChatProfileEntity)

    @Query("UPDATE chat_profiles SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("DELETE FROM chat_profiles WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface ProviderGroupDao {
    @Query("SELECT * FROM provider_groups ORDER BY sortOrder ASC, id ASC")
    fun getAllGroups(): Flow<List<ProviderGroupEntity>>

    @Query("SELECT * FROM provider_groups WHERE id = :id")
    suspend fun getGroupById(id: String): ProviderGroupEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(group: ProviderGroupEntity)

    @Query("UPDATE provider_groups SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("DELETE FROM provider_groups WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface LlmProviderDao {
    @Query("SELECT * FROM llm_providers ORDER BY sortOrder ASC, id ASC")
    fun getAllProviders(): Flow<List<LlmProviderEntity>>

    @Query("SELECT * FROM llm_providers WHERE id = :id")
    suspend fun getProviderById(id: String): LlmProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(provider: LlmProviderEntity)

    @Query("UPDATE llm_providers SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("UPDATE llm_providers SET groupId = :groupId WHERE id = :id")
    suspend fun updateGroup(id: String, groupId: String?)

    @Query("UPDATE llm_providers SET groupId = NULL WHERE groupId = :groupId")
    suspend fun clearGroup(groupId: String)

    @Query("DELETE FROM llm_providers WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface LlmModelsDao {
    @Query("SELECT * FROM llm_models WHERE id = :providerId")
    suspend fun getModelsForProvider(providerId: String): LlmModelsEntity?

    @Query("SELECT * FROM llm_models")
    fun getAllModels(): Flow<List<LlmModelsEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(models: LlmModelsEntity)
}

@Dao
interface McpServerDao {
    @Query("SELECT * FROM mcp_servers ORDER BY sortOrder ASC, id ASC")
    fun getAllServers(): Flow<List<McpServerEntity>>

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun getServerById(id: String): McpServerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(server: McpServerEntity)

    @Query("UPDATE mcp_servers SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface SpeechServiceDao {
    @Query("SELECT * FROM speech_services ORDER BY sortOrder ASC, id ASC")
    fun getAllSpeechServices(): Flow<List<SpeechServiceEntity>>

    @Query("SELECT * FROM speech_services WHERE id = :id")
    suspend fun getServiceById(id: String): SpeechServiceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(service: SpeechServiceEntity)

    @Query("UPDATE speech_services SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: String, sortOrder: Int)

    @Query("DELETE FROM speech_services WHERE id = :id")
    suspend fun deleteById(id: String)
}
