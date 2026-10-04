package org.starfall.multigateway.ui.mcp

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import org.starfall.multigateway.R
import org.starfall.multigateway.data.model.ToolSettings
import org.starfall.multigateway.data.model.ToolDefinition

@Composable
internal fun McpToolsTab(
    serverId: String, tools: List<ToolDefinition>?, loading: Boolean, error: String?, ready: Boolean,
    toolSettings: ToolSettings, onSetToolEnabled: (String, String, Boolean) -> Unit,
    onRefresh: () -> Unit, modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.available_tools), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (tools == null) stringResource(R.string.no_cached_tool_list) else stringResource(R.string.cached_tools_count, tools.size),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onRefresh, enabled = ready && !loading) {
                Text(stringResource(if (loading) R.string.refreshing else R.string.refresh))
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (!ready) {
            ToolListPlaceholder(R.string.mcp_enter_server_first)
            return@Column
        }
        if (error != null) Text(error.lineSequence().firstOrNull().orEmpty(),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        when {
            tools == null -> ToolListPlaceholder(R.string.no_cached_tools_yet)
            tools.isEmpty() -> ToolListPlaceholder(R.string.server_reported_no_tools)
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(tools, key = { it.name }) { tool ->
                    McpToolItem(tool, toolSettings.mcpTools[serverId]?.get(tool.originalName) != false) {
                        onSetToolEnabled(serverId, tool.originalName, it)
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolListPlaceholder(resource: Int) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(resource), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
    }
}

@Composable
private fun McpToolItem(tool: ToolDefinition, enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    var expanded by remember(tool.name) { mutableStateOf(false) }
    val properties = tool.schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val required = (tool.schema["required"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet().orEmpty()
    Surface(shape = RoundedCornerShape(16.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column {
            ListItem(colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                headlineContent = { Text(tool.originalName, fontWeight = FontWeight.Medium) },
                supportingContent = {
                    if (tool.description.isNotBlank()) Text(tool.description, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = { Icon(Icons.Outlined.Extension, null) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(enabled, onCheckedChange = onEnabledChange)
                        Spacer(Modifier.width(6.dp))
                        Icon(if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                            stringResource(if (expanded) R.string.collapse_tool_details else R.string.expand_tool_details))
                    }
                })
            if (expanded) {
                HorizontalDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (tool.description.isNotBlank()) {
                        Text(stringResource(R.string.common_description), style = MaterialTheme.typography.labelLarge)
                        Text(tool.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(stringResource(R.string.common_parameters), style = MaterialTheme.typography.labelLarge)
                    if (properties.isEmpty()) Text(stringResource(R.string.no_parameters), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else properties.forEach { (name, raw) -> McpToolParameter(name, raw, name in required) }
                }
            }
        }
    }
}

@Composable
private fun McpToolParameter(name: String, raw: JsonElement, required: Boolean) {
    val schema = raw as? JsonObject ?: JsonObject(emptyMap())
    val type = schema["type"]?.jsonPrimitive?.contentOrNull ?: if (schema["enum"] is JsonArray) "enum" else "any"
    val description = schema["description"]?.jsonPrimitive?.contentOrNull
    val enumValues = (schema["enum"] as? JsonArray)?.joinToString(", ") { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text(type, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (required) {
                Spacer(Modifier.width(6.dp))
                Text("required", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
        }
        if (!description.isNullOrBlank()) Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!enumValues.isNullOrBlank()) Text("Allowed: $enumValues", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}
