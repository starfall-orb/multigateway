package org.starfall.multigateway.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class McpProtocol {
    @SerialName("streamable_http") STREAMABLE_HTTP,
    @SerialName("sse") SSE
}

@Serializable
enum class McpAuthMethod {
    NONE,
    BEARER_TOKEN,
    QUERY_PARAM,
    CUSTOM_HEADER,
    OAUTH2
}

@Serializable
data class McpAuthorization(
    val method: McpAuthMethod = McpAuthMethod.NONE,
    val key: String? = null,
    val value: String? = null,
    val oauthClientId: String? = null,
    val oauthClientSecret: String? = null,
    val oauthAuthorized: Boolean = false
) {
    val token: String get() = value.orEmpty()
}

@Serializable
data class McpInfo(
    val id: String,
    val name: String,
    val protocol: McpProtocol = McpProtocol.STREAMABLE_HTTP,
    val url: String? = null,
    val headers: Map<String, String>? = null,
    val auth: McpAuthorization = McpAuthorization(),
    val cachedTools: List<ToolDefinition>? = null,
    val sortOrder: Int = Int.MAX_VALUE,
    val icon: String? = null
)

const val CONTENT_API_MCP_ENDPOINT = "https://serverweb.serv00.net/mcp"

fun String.isContentApiName(): Boolean =
    trim().lowercase().replace("-", "").replace(" ", "") == "contentapi"

/** Returns the effective endpoint while allowing the built-in Content API URL to stay hidden. */
fun McpInfo.resolvedUrl(): String? = url?.trim()?.takeIf(String::isNotEmpty)
    ?: CONTENT_API_MCP_ENDPOINT.takeIf { name.isContentApiName() }
