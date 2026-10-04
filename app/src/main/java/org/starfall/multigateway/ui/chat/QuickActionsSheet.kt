package org.starfall.multigateway.ui.chat

import org.starfall.multigateway.ui.components.AppBottomSheet
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.tools.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickActionsSheet(onDismiss: () -> Unit) {
    val controls = LocalToolControls.current
    AppBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Tools Manage", style = MaterialTheme.typography.titleLarge)
            Text("System tools", style = MaterialTheme.typography.titleMedium)
            listOf("generate_image", "generate_video").forEach { name ->
                val cfg = controls.settings.system[name] ?: SystemToolConfig()
                val available = systemMediaToolAvailable(name, cfg, controls.providers)
                ToolSwitch(
                    if (name == "generate_image") "Create image" else "Create video",
                    cfg.enabled,
                    enabled = true
                ) { controls.setSystem(name, cfg.copy(enabled = it)) }
                if (!available) {
                    Text(
                        "Model not set. AI will be notified if called.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
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
                ToolSwitch(
                    server.name,
                    controls.settings.quickMcp[server.id] != false,
                    enabled = true
                ) { controls.setMcp(server.id, it) }
            }
        }
    }
}
