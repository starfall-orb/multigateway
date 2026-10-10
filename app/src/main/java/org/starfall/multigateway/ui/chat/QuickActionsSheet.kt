package org.starfall.multigateway.ui.chat

import org.starfall.multigateway.ui.components.AppBottomSheet
import org.starfall.multigateway.ui.components.EntityIcon
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.tools.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionsSheet(onDismiss: () -> Unit) {
    val controls = LocalToolControls.current
    AppBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Tools Manage", style = MaterialTheme.typography.titleLarge)
            Text("System tools", style = MaterialTheme.typography.titleMedium)
            listOf("generate_image", "generate_video").forEach { name ->
                val cfg = controls.settings.system[name] ?: SystemToolConfig()
                val provider = controls.providers.firstOrNull { it.id == cfg.providerId }
                val model = provider?.config?.modelConfigs?.get(cfg.modelId)
                val modelName = model?.displayName?.ifBlank { cfg.modelId }
                    ?.takeIf { it.isNotBlank() }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (modelName != null) {
                            EntityIcon(
                                image = model?.icon,
                                modifier = Modifier.size(44.dp),
                                text = modelName.take(2).uppercase(),
                                fallback = if (name == "generate_image") Icons.Outlined.Image else Icons.Outlined.Videocam,
                                matchName = modelName,
                                model = true
                            )
                            Spacer(Modifier.width(12.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (name == "generate_image") "Image Generation" else "Video Generation",
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1
                            )
                            if (modelName != null) {
                                Text(
                                    modelName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Switch(
                            checked = cfg.enabled,
                            onCheckedChange = { controls.setSystem(name, cfg.copy(enabled = it)) }
                        )
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            Text("MCP servers", style = MaterialTheme.typography.titleMedium)
            if (controls.servers.isEmpty()) {
                Text(
                    "No MCP servers configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            controls.servers.forEach { server ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        EntityIcon(
                            image = server.icon,
                            modifier = Modifier.size(44.dp),
                            text = server.name.take(2).uppercase(),
                            fallback = Icons.Outlined.Extension,
                            matchName = server.name
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                server.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                server.resolvedUrl().orEmpty().ifBlank { "MCP server" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${server.cachedTools?.size ?: 0} cached tools",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Switch(
                            checked = controls.settings.quickMcp[server.id] != false,
                            onCheckedChange = { controls.setMcp(server.id, it) }
                        )
                    }
                }
            }
        }
    }
}
