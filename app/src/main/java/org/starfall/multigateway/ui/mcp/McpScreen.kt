package org.starfall.multigateway.ui.mcp

import org.starfall.multigateway.ui.components.AppAlertDialog as AlertDialog

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.starfall.multigateway.R
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.components.FadeGridListContent
import org.starfall.multigateway.ui.components.longPressReorder
import org.starfall.multigateway.ui.components.moved
import org.starfall.multigateway.ui.components.windowHeightIn
import org.starfall.multigateway.ui.navigation.SlideScreenContent
import java.util.UUID

private data class McpEditor(val server: McpInfo, val isNew: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(
    mcpServers: List<McpInfo>, isGridView: Boolean = false, onToggleGridView: ((Boolean) -> Unit)? = null,
    toolsCache: Map<String, List<ToolDefinition>>, toolErrors: Map<String, String>, toolsLoading: Set<String>,
    toolSettings: ToolSettings, onSetToolEnabled: (String, String, Boolean) -> Unit,
    onSaveMcpServer: (McpInfo) -> Unit, onDeleteMcpServer: (String) -> Unit, onReorderMcpServers: (List<String>) -> Unit,
    onRefreshTools: suspend (McpInfo) -> Result<List<ToolDefinition>>, onAuthorizeOAuth: suspend (McpInfo) -> Result<McpInfo>,
    onClearOAuth: suspend (McpInfo) -> McpInfo, onBack: () -> Unit
) {
    var editor by remember { mutableStateOf<McpEditor?>(null) }
    var deletingServerId by remember { mutableStateOf<String?>(null) }
    var selectedToolError by remember { mutableStateOf<Pair<String, String>?>(null) }
    var orderedServers by remember(mcpServers) { mutableStateOf(mcpServers) }
    val context = LocalContext.current
    SlideScreenContent(editor = editor, label = stringResource(R.string.mcp_editor)) { page ->
        if (page != null) AddOrEditMcpScreenContent(
            initialServer = page.server, isNew = page.isNew, onRefreshTools = onRefreshTools,
            onAuthorizeOAuth = onAuthorizeOAuth, onClearOAuth = onClearOAuth,
            cachedTools = toolsCache[page.server.id] ?: page.server.cachedTools, cachedError = toolErrors[page.server.id],
            cachedLoading = page.server.id in toolsLoading, toolSettings = toolSettings, onSetToolEnabled = onSetToolEnabled,
             onDismiss = { editor = null }, onSave = onSaveMcpServer
        ) else Scaffold(topBar = {
            TopAppBar(title = {
                Column {
                    Text(stringResource(R.string.mcp_servers_title), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
                    Text(stringResource(R.string.mcp_servers_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                }
            }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                actions = {
                    IconButton(onClick = { onToggleGridView?.invoke(!isGridView) }) {
                        Icon(if (isGridView) Icons.Default.List else Icons.Default.GridView, stringResource(R.string.toggle_grid_list))
                    }
                    IconButton(onClick = {
                        editor = McpEditor(McpInfo(id = UUID.randomUUID().toString(), name = "", protocol = McpProtocol.STREAMABLE_HTTP,
                            url = null, headers = emptyMap()), true)
                    }) { Icon(Icons.Default.Add, stringResource(R.string.add_server)) }
                })
        }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (mcpServers.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Outlined.Extension, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.no_mcp_servers), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.no_mcp_servers_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                    }
                } else FadeGridListContent(isGrid = isGridView, modifier = Modifier.fillMaxSize()) { gridMode ->
                    LazyVerticalGrid(columns = GridCells.Fixed(if (gridMode) 2 else 1),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(orderedServers, key = { _, item -> item.id }) { index, server ->
                        McpUnifiedCard(server = server, isGrid = gridMode,
                            modifier = Modifier.animateItem(placementSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
                                .longPressReorder(index = index, itemCount = orderedServers.size, columns = if (gridMode) 2 else 1,
                                    onMove = { from, to -> orderedServers = orderedServers.moved(from, to) },
                                    onDrop = { onReorderMcpServers(orderedServers.map { it.id }) }),
                            toolCount = (toolsCache[server.id] ?: server.cachedTools)?.size, toolError = toolErrors[server.id], loading = server.id in toolsLoading,
                            onToolErrorClick = { selectedToolError = server.name to it },
                            onEdit = { editor = McpEditor(server, false) }, onDelete = { deletingServerId = server.id })
                    }
                }
                }
            }
        }
    }
    if (deletingServerId != null) AlertDialog(onDismissRequest = { deletingServerId = null },
        title = { Text(stringResource(R.string.delete_mcp_server_title)) }, text = { Text(stringResource(R.string.delete_mcp_server_message)) },
        confirmButton = { Button(onClick = { deletingServerId?.let(onDeleteMcpServer); deletingServerId = null },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.common_delete)) } },
        dismissButton = { TextButton(onClick = { deletingServerId = null }) { Text(stringResource(R.string.common_cancel)) } })
    selectedToolError?.let { (serverName, error) ->
        AlertDialog(onDismissRequest = { selectedToolError = null }, title = { Text(stringResource(R.string.mcp_tool_discovery_error_title, serverName)) },
            text = {
                Box(Modifier.fillMaxWidth().windowHeightIn(maxFraction = 0.6f).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(error, style = MaterialTheme.typography.bodySmall) }
                }
            }, confirmButton = { TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.mcp_tool_discovery_error_clipboard), error))
            }) { Text(stringResource(R.string.common_copy)) } },
            dismissButton = { TextButton(onClick = { selectedToolError = null }) { Text(stringResource(R.string.common_close)) } })
    }
}
