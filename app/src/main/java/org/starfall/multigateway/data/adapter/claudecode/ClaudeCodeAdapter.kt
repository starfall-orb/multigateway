package org.starfall.multigateway.data.adapter.claudecode

import android.content.Context
import kotlinx.serialization.json.*
import org.starfall.multigateway.data.adapter.common.OAuthAccountAdapter
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.service.AttachmentResolver
import org.starfall.multigateway.data.tools.text

internal class ClaudeCodeAdapter(context: Context, attachments: AttachmentResolver) : OAuthAccountAdapter(
    context, attachments, ProviderType.CLAUDE_CODE,
    "9d1c250a-e61b-44d9-88ed-5944d1962f5e", "https://claude.ai/oauth/authorize",
    "https://platform.claude.com/v1/oauth/token",
    "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload",
    54545, "/callback", jsonTokens = true
) {
    override suspend fun prepareAuthenticatedProvider(provider: LlmProviderInfo): LlmProviderInfo {
        val token = ensureToken(provider)
        return provider.copy(type = ProviderType.ANTHROPIC,
            auth = Authorization(AuthMethod.CUSTOM_HEADER, "Authorization", "Bearer ${token.accessToken}"),
            config = provider.config.copy(headers = provider.config.headers + mapOf(
                "User-Agent" to "claude-cli/2.1.161 (external, cli)", "x-app" to "cli",
                "anthropic-version" to "2023-06-01",
                "anthropic-dangerous-direct-browser-access" to "true",
                "anthropic-beta" to "claude-code-20250219,oauth-2025-04-20,interleaved-thinking-2025-05-14,prompt-caching-scope-2026-01-05,effort-2025-11-24,context-management-2025-06-27,extended-cache-ttl-2025-04-11")))
    }
    override suspend fun fetchModels(provider: LlmProviderInfo): List<String> {
        val wire = prepareAuthenticatedProvider(provider)
        return (http.json(http.request(wire.baseUrl.trimEnd('/') + "/models", wire).get().build())["data"] as? JsonArray)
            .orEmpty().mapNotNull { it.jsonObject.text("id").takeIf(String::isNotBlank) }
    }
    override fun normalizeToolRequest(sourceProvider: LlmProviderInfo, wireProvider: LlmProviderInfo,
        body: JsonObject, systemPrompt: String): JsonObject {
        val system = body["system"]
        val blocks = when (system) {
            is JsonArray -> system.toList()
            is JsonPrimitive -> listOf(buildJsonObject { put("type", "text"); put("text", system.content) })
            else -> emptyList()
        }
        val identity = buildJsonObject { put("type", "text"); put("text", "You are Claude Code, Anthropic's official CLI for Claude.") }
        return JsonObject(body + ("system" to JsonArray(listOf(identity) + blocks)))
    }
}
