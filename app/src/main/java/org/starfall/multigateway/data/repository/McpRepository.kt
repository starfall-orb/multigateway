package org.starfall.multigateway.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import org.starfall.multigateway.data.local.db.AppDatabase
import org.starfall.multigateway.data.local.db.SecretCipher
import org.starfall.multigateway.data.local.db.entities.*
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.McpService
import org.starfall.multigateway.data.service.IconStore

@Serializable
private data class McpPersistedHttpConfig(
    val headers: Map<String, String>? = null,
    val auth: McpAuthorization = McpAuthorization(),
    val icon: String? = null
)

class McpRepository(private val db: AppDatabase, private val service: McpService, private val icons: IconStore? = null) {
    suspend fun discoverTools(server: McpInfo) = service.discover(server)

    private val dao = db.mcpServerDao()

    val allServers: Flow<List<McpInfo>> = dao.getAllServers().map { entities ->
        entities.map { entityToModel(it) }
    }

    suspend fun getById(id: String): McpInfo? {
        val entity = dao.getServerById(id) ?: return null
        return entityToModel(entity)
    }

    suspend fun saveServer(server: McpInfo) {
        val previous = getById(server.id)
        server.icon?.takeIf { it != previous?.icon || server.name != previous?.name }?.let {
            icons?.cache(server.name, it)
        }
        dao.insertOrUpdate(modelToEntity(server))
    }

    suspend fun saveCachedTools(serverId: String, tools: List<ToolDefinition>) {
        val server = getById(serverId) ?: return
        saveServer(server.copy(cachedTools = tools))
    }

    suspend fun deleteServer(id: String) {
        dao.deleteById(id)
    }

    suspend fun reorderServers(ids: List<String>) {
        ids.forEachIndexed { index, id -> dao.updateSortOrder(id, index) }
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
