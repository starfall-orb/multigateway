package org.starfall.multigateway.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.starfall.multigateway.data.local.db.AppDatabase
import org.starfall.multigateway.data.local.db.SecretCipher
import org.starfall.multigateway.data.local.db.entities.*
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.McpService
import org.starfall.multigateway.data.service.McpOAuthService
import org.starfall.multigateway.data.service.IconStore

@Serializable
private data class McpPersistedHttpConfig(
    val headers: Map<String, String>? = null,
    val auth: McpAuthorization = McpAuthorization(),
    val icon: String? = null
)

class McpRepository(
    private val db: AppDatabase,
    private val service: McpService,
    private val icons: IconStore? = null,
    private val oauth: McpOAuthService? = null
) {
    suspend fun discoverTools(server: McpInfo) = service.discover(server)

    suspend fun authorizeOAuth(server: McpInfo): Result<McpInfo> = runCatching {
        val authorized = (oauth ?: error("MCP OAuth is unavailable")).authorize(server)
        if (getById(server.id) != null) saveServer(authorized)
        authorized
    }

    suspend fun clearOAuth(server: McpInfo): McpInfo {
        oauth?.clear(server.id)
        val cleared = server.copy(auth = server.auth.copy(value = null, oauthAuthorized = false))
        if (getById(server.id) != null) saveServer(cleared)
        return cleared
    }

    private val dao = db.mcpServerDao()

    private val stored: Flow<List<McpInfo>> = dao.getAllServers().map { entities ->
        entities.map { entityToModel(it) }
    }

    private val state = ImmediateState(stored)
    val allServers: Flow<List<McpInfo>> = state.flow

    suspend fun getById(id: String): McpInfo? {
        state.value?.let { items -> return items.firstOrNull { it.id == id } }
        return withContext(Dispatchers.IO) { dao.getServerById(id)?.let(::entityToModel) }
    }

    suspend fun saveServer(server: McpInfo) {
        state.mutate({ it.upsert(server) { item -> item.id }.sortedBy { it.sortOrder } }) {
            dao.insertOrUpdate(modelToEntity(server))
        }
    }

    suspend fun saveCachedTools(serverId: String, tools: List<ToolDefinition>) {
        val server = getById(serverId) ?: return
        saveServer(server.copy(cachedTools = tools))
    }

    suspend fun deleteServer(id: String) {
        state.mutate({ items -> items.filterNot { it.id == id } }) {
            oauth?.clear(id)
            dao.deleteById(id)
        }
    }

    suspend fun reorderServers(ids: List<String>) {
        state.mutate({ items -> items.map { item -> ids.indexOf(item.id).takeIf { it >= 0 }?.let { item.copy(sortOrder = it) } ?: item }.sortedBy { it.sortOrder } }) {
            db.withTransaction {
                ids.forEachIndexed { index, id -> dao.updateSortOrder(id, index) }
            }
        }
    }

    private fun entityToModel(entity: McpServerEntity): McpInfo {
        val savedProtocol = runCatching { McpProtocol.valueOf(entity.protocol) }.getOrNull()
        val protocol = savedProtocol ?: McpProtocol.STREAMABLE_HTTP
        val persisted = entity.headersJson?.let(SecretCipher::decrypt)?.let { raw ->
            val legacyHeaders = runCatching {
                json.decodeFromString<Map<String, String>>(raw)
            }.getOrNull()
            if (legacyHeaders != null) {
                McpPersistedHttpConfig(headers = legacyHeaders)
            } else {
                runCatching { json.decodeFromString<McpPersistedHttpConfig>(raw) }.getOrNull()
            }
        } ?: McpPersistedHttpConfig()

        return McpInfo(
            id = entity.id,
            name = entity.name,
            protocol = protocol,
            // Preserve obsolete server records for editing, but never treat a saved command as a URL.
            url = entity.url?.let(SecretCipher::decrypt).takeIf { savedProtocol != null },
            headers = persisted.headers,
            auth = persisted.auth,
            cachedTools = entity.cachedToolsJson?.let { raw ->
                runCatching { json.decodeFromString<List<ToolDefinition>>(raw) }.getOrNull()
            },
            sortOrder = entity.sortOrder,
            icon = persisted.icon
        )
    }

    private fun modelToEntity(server: McpInfo): McpServerEntity {
        return McpServerEntity(
            id = server.id,
            name = server.name,
            protocol = server.protocol.name,
            url = server.url?.let(SecretCipher::encrypt),
            headersJson = SecretCipher.encrypt(
                json.encodeToString(McpPersistedHttpConfig(server.headers, server.auth, server.icon))
            ),
            cachedToolsJson = server.cachedTools?.let { json.encodeToString(it) },
            sortOrder = server.sortOrder
        )
    }
}
