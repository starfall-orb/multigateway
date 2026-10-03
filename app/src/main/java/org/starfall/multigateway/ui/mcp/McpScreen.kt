package org.starfall.multigateway.ui.mcp
import org.starfall.multigateway.ui.components.EntityIcon
import org.starfall.multigateway.ui.components.IconPickerRow
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.starfall.multigateway.ui.navigation.LocalScreenTransitionActive
import org.starfall.multigateway.ui.navigation.SlideScreenContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import androidx.compose.ui.focus.onFocusChanged
import org.starfall.multigateway.R
import org.starfall.multigateway.data.model.bearerHeaderValue
import org.starfall.multigateway.data.model.McpAuthMethod
import org.starfall.multigateway.data.model.McpAuthorization
import org.starfall.multigateway.data.model.McpInfo
import org.starfall.multigateway.data.model.McpProtocol
import org.starfall.multigateway.data.model.ToolDefinition
import org.starfall.multigateway.data.model.ToolSettings
import java.util.UUID
import org.starfall.multigateway.ui.components.ItemOverflowMenu
import org.starfall.multigateway.ui.components.MorphingCardLayout
import org.starfall.multigateway.ui.components.longPressReorder
import org.starfall.multigateway.ui.components.moved

private data class McpEditor(val server: McpInfo, val isNew: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpScreen(
    mcpServers: List<McpInfo>,
    isGridView: Boolean = false,
    onToggleGridView: ((Boolean) -> Unit)? = null,
    toolsCache: Map<String, List<ToolDefinition>>,
    toolErrors: Map<String, String>,
    toolsLoading: Set<String>,
    toolSettings: ToolSettings,
    onSetToolEnabled: (String, String, Boolean) -> Unit,
    onSaveMcpServer: (McpInfo) -> Unit,
    onDeleteMcpServer: (String) -> Unit,
    onReorderMcpServers: (List<String>) -> Unit,
    onRefreshTools: suspend (McpInfo) -> Result<List<ToolDefinition>>,
    onBack: () -> Unit
) {
    var editor by remember { mutableStateOf<McpEditor?>(null) }
    var deletingServerId by remember { mutableStateOf<String?>(null) }
    var selectedToolError by remember { mutableStateOf<Pair<String, String>?>(null) }
    var orderedServers by remember(mcpServers) { mutableStateOf(mcpServers) }
    val context = LocalContext.current

    SlideScreenContent(
        editor = editor,
        label = stringResource(R.string.mcp_editor)
    ) { page ->
    if (page != null) {
        AddOrEditMcpScreenContent(
            initialServer = page.server,
            isNew = page.isNew,
            onRefreshTools = onRefreshTools,
            cachedTools = toolsCache[page.server.id] ?: page.server.cachedTools,
            cachedError = toolErrors[page.server.id],
            cachedLoading = page.server.id in toolsLoading,
            toolSettings = toolSettings,
            onSetToolEnabled = onSetToolEnabled,
            onDismiss = {
                editor = null
            },
            onSave = { saved ->
                onSaveMcpServer(saved)
                editor = null
            }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.mcp_servers_title),
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = stringResource(R.string.mcp_servers_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = { onToggleGridView?.invoke(!isGridView) }) {
                            Icon(
                                imageVector = if (isGridView) Icons.Default.List else Icons.Default.GridView,
                                contentDescription = stringResource(R.string.toggle_grid_list)
                            )
                        }
                        IconButton(onClick = {
                            editor = McpEditor(McpInfo(
                                id = UUID.randomUUID().toString(),
                                name = "",
                                protocol = McpProtocol.STREAMABLE_HTTP,
                                url = null,
                                headers = emptyMap()
                            ), true)
                        }) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_server))
                        }
                    }
                )
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                if (mcpServers.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Outlined.Extension,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.outline,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(stringResource(R.string.no_mcp_servers), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.no_mcp_servers_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(if (isGridView) 2 else 1),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(orderedServers, key = { _, item -> item.id }) { index, server ->
                                    McpUnifiedCard(
                                server = server,
                                isGrid = isGridView,
                                modifier = Modifier
                                    .animateItem(
                                        placementSpec = spring(
                                            dampingRatio = Spring.DampingRatioNoBouncy,
                                            stiffness = Spring.StiffnessMediumLow
                                        )
                                    )
                                    .longPressReorder(
                                        index = index,
                                        itemCount = orderedServers.size,
                                        columns = if (isGridView) 2 else 1,
                                        onMove = { from, to -> orderedServers = orderedServers.moved(from, to) },
                                        onDrop = { onReorderMcpServers(orderedServers.map { it.id }) }
                                    ),
                                toolCount = (toolsCache[server.id] ?: server.cachedTools)?.size,
                                toolError = toolErrors[server.id],
                                loading = server.id in toolsLoading,
                                onToolErrorClick = { error -> selectedToolError = server.name to error },
                                onEdit = { editor = McpEditor(server, false) },
                                onDelete = { deletingServerId = server.id }
                            )
                        }
                    }
                }
            }
        }
    }

    }

    if (deletingServerId != null) {
        AlertDialog(
            onDismissRequest = { deletingServerId = null },
            title = { Text(stringResource(R.string.delete_mcp_server_title)) },
            text = { Text(stringResource(R.string.delete_mcp_server_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        deletingServerId?.let(onDeleteMcpServer)
                        deletingServerId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deletingServerId = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    selectedToolError?.let { (serverName, error) ->
        AlertDialog(
            onDismissRequest = { selectedToolError = null },
            title = { Text(stringResource(R.string.mcp_tool_discovery_error_title, serverName)) },
            text = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.mcp_tool_discovery_error_clipboard), error))
                    }
                ) { Text(stringResource(R.string.common_copy)) }
            },
            dismissButton = {
                TextButton(onClick = { selectedToolError = null }) { Text(stringResource(R.string.common_close)) }
            }
        )
    }
}

@Composable
fun McpUnifiedCard(
    server: McpInfo,
    isGrid: Boolean,
    modifier: Modifier = Modifier,
    toolCount: Int?,
    toolError: String?,
    loading: Boolean,
    onToolErrorClick: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val shapeCorner by animateDpAsState(
        targetValue = if (isGrid) 20.dp else 16.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "mcpShapeCorner"
    )

    Surface(
        shape = RoundedCornerShape(shapeCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onEdit() }
    ) {
        MorphingCardLayout(
            isGrid = isGrid,
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            icon = {
                EntityIcon(server.icon, Modifier.size(42.dp), fallback = Icons.Outlined.Extension, matchName = server.name)
            },
            actions = {
                ItemOverflowMenu(
                    onEdit = onEdit,
                    onDelete = onDelete,
                    deleteColor = MaterialTheme.colorScheme.error
                )
            },
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = server.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.mcp_protocol, mcpProtocolLabel(server.protocol)),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = server.url ?: stringResource(R.string.mcp_no_endpoint),
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    when {
                        toolError != null -> {
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f))
                                    .clickable { onToolErrorClick(toolError) }
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.ErrorOutline,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.mcp_error_details),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        loading -> Text(
                            stringResource(R.string.loading_tools),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.primary
                        )
                        toolCount != null -> Text(
                            stringResource(R.string.cached_tools_count, toolCount),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddOrEditMcpScreenContent(
    initialServer: McpInfo,
    isNew: Boolean,
    onRefreshTools: suspend (McpInfo) -> Result<List<ToolDefinition>>,
    cachedTools: List<ToolDefinition>?,
    cachedError: String?,
    cachedLoading: Boolean,
    toolSettings: ToolSettings,
    onSetToolEnabled: (String, String, Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSave: (McpInfo) -> Unit
) {
    var serverIcon by remember(initialServer.id) { mutableStateOf(initialServer.icon) }
    var iconImporting by remember { mutableStateOf(false) }
    var name by remember(initialServer.id) { mutableStateOf(initialServer.name) }
    var protocol by remember(initialServer.id) { mutableStateOf(initialServer.protocol) }
    var url by remember(initialServer.id) { mutableStateOf(initialServer.url.orEmpty()) }
    var headers by remember(initialServer.id) { mutableStateOf(initialServer.headers.orEmpty().toList()) }
    var authMethod by remember(initialServer.id) { mutableStateOf(if (initialServer.auth.method == McpAuthMethod.CUSTOM_HEADER) McpAuthMethod.BEARER_TOKEN else initialServer.auth.method) }
    var authKey by remember(initialServer.id) { mutableStateOf(initialServer.auth.key.orEmpty().ifBlank { if (initialServer.auth.method == McpAuthMethod.QUERY_PARAM) "key" else "Authorization" }) }
    var authValue by remember(initialServer.id) { mutableStateOf(initialServer.auth.value.orEmpty()) }
    var selectedTab by remember(initialServer.id) { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    var oauthAuthUrl by remember(initialServer.id, url) {
        val guess = if (url.isNotBlank()) {
            if (url.endsWith("/")) url + "oauth/authorize" else url + "/oauth/authorize"
        } else ""
        mutableStateOf(guess)
    }
    var oauthClientId by remember(initialServer.id) { mutableStateOf("multigateway") }

    LaunchedEffect(initialServer.id) {
        OAuthReceiver.tokenFlow.collect { receivedToken ->
            if (receivedToken != null) {
                authValue = receivedToken
                OAuthReceiver.consume(receivedToken)
            }
        }
    }

    fun currentAuth() = McpAuthorization(
        method = authMethod,
        key = if (authMethod in listOf(McpAuthMethod.BEARER_TOKEN, McpAuthMethod.QUERY_PARAM)) authKey.trim() else null,
        value = if (authMethod == McpAuthMethod.NONE) null else if (authMethod == McpAuthMethod.BEARER_TOKEN) bearerHeaderValue(authKey.ifBlank { "Authorization" }, authValue) else authValue.trim()
    )

    fun currentServer(): McpInfo = initialServer.copy(
        name = name.trim(),
        icon = serverIcon,
        protocol = protocol,
        url = url.trim().ifEmpty { null },
        headers = headers.map { it.first.trim() to it.second }
            .filter { it.first.isNotEmpty() }
            .toMap()
            .ifEmpty { null },
        auth = currentAuth()
    )

    val authValid = authValue.isBlank() || authKey.isBlank() || authMethod !in listOf(McpAuthMethod.BEARER_TOKEN, McpAuthMethod.QUERY_PARAM) ||
        (authKey.isNotBlank() && !authKey.contains(':') && authKey.none { it <= ' ' || it.code >= 127 })
    val canSave = name.isNotBlank() && url.isNotBlank() && authValid && headers.all {
        it.first.isNotBlank() && !it.first.contains(':') &&
            !it.first.any { ch -> ch == '\r' || ch == '\n' } &&
            !it.second.any { ch -> ch == '\r' || ch == '\n' }
    }

    fun refreshTools() {
        if (!canSave || cachedLoading) return
        scope.launch { onRefreshTools(currentServer()) }
    }

    androidx.activity.compose.BackHandler(enabled = LocalScreenTransitionActive.current, onBack = onDismiss)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp
                ) {
                    Column(modifier = Modifier.statusBarsPadding()) {
                        TopAppBar(
                            windowInsets = WindowInsets(0, 0, 0, 0),
                            title = {
                                Text(
                                    if (isNew) stringResource(R.string.add_mcp_server_title) else stringResource(R.string.configure_mcp_server_title),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = onDismiss) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                                }
                            },
                            actions = {
                                Button(
                                    onClick = { onSave(currentServer()) },
                                    enabled = canSave && !iconImporting,
                                    modifier = Modifier.padding(end = 8.dp)
                                ) {
                                    Text(stringResource(R.string.common_save))
                                }
                            }
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
            ) {

                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selectedTab == 0, { selectedTab = 0 }, text = { Text(stringResource(R.string.mcp_basic_settings), fontWeight = FontWeight.SemiBold) })
                    Tab(selectedTab == 1, { selectedTab = 1 }, text = { Text(stringResource(R.string.mcp_tools), fontWeight = FontWeight.SemiBold) })
                }

                if (selectedTab == 0) {
                    McpBasicSettings(
                        name = name,
                        icon = serverIcon,
                        onIconChange = { serverIcon = it },
                        onIconBusyChange = { iconImporting = it },
                        onNameChange = { name = it },
                        protocol = protocol,
                        onProtocolChange = { protocol = it },
                        url = url,
                        onUrlChange = { url = it },
                        authMethod = authMethod,
                        onAuthMethodChange = { method ->
                            authMethod = method
                            authKey = if (method == McpAuthMethod.QUERY_PARAM) "key" else "Authorization"
                        },
                        authKey = authKey,
                        onAuthKeyChange = { authKey = it },
                        authValue = authValue,
                        onAuthValueChange = { authValue = it },
                        authValid = authValid,
                        headers = headers,
                        onHeadersChange = { headers = it },
                        oauthAuthUrl = oauthAuthUrl,
                        onOauthAuthUrlChange = { oauthAuthUrl = it },
                        oauthClientId = oauthClientId,
                        onOauthClientIdChange = { oauthClientId = it },
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    McpToolsTab(
                        serverId = initialServer.id,
                        tools = cachedTools,
                        loading = cachedLoading,
                        error = cachedError,
                        ready = canSave,
                        toolSettings = toolSettings,
                        onSetToolEnabled = onSetToolEnabled,
                        onRefresh = ::refreshTools,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun McpBasicSettings(
    name: String,
    icon: String?,
    onIconChange: (String?) -> Unit,
    onIconBusyChange: (Boolean) -> Unit,
    onNameChange: (String) -> Unit,
    protocol: McpProtocol,
    onProtocolChange: (McpProtocol) -> Unit,
    url: String,
    onUrlChange: (String) -> Unit,
    authMethod: McpAuthMethod,
    onAuthMethodChange: (McpAuthMethod) -> Unit,
    authKey: String,
    onAuthKeyChange: (String) -> Unit,
    authValue: String,
    onAuthValueChange: (String) -> Unit,
    authValid: Boolean,
    headers: List<Pair<String, String>>,
    onHeadersChange: (List<Pair<String, String>>) -> Unit,
    oauthAuthUrl: String,
    onOauthAuthUrlChange: (String) -> Unit,
    oauthClientId: String,
    onOauthClientIdChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var oauthAdvancedExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        IconPickerRow(icon, onIconChange, fallback = Icons.Outlined.Extension, onBusyChange = onIconBusyChange, matchName = name)
        Text(stringResource(R.string.display_name), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            placeholder = { Text(stringResource(R.string.common_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        McpDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.legacy_sse), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = protocol == McpProtocol.SSE, onCheckedChange = {
                onProtocolChange(if (it) McpProtocol.SSE else McpProtocol.STREAMABLE_HTTP)
            })
        }

        McpDivider()
        Text(stringResource(R.string.server_url), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.server_url_help, mcpProtocolLabel(protocol)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            placeholder = { Text(stringResource(R.string.common_url)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        if (url.trim().startsWith("http://", true)) {
            Text(
                stringResource(R.string.http_unencrypted_mcp_warning),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        McpDivider()
        Text(stringResource(R.string.mcp_auth), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.mcp_auth_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        var authExpanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { authExpanded = true }) {
                Text(stringResource(R.string.mcp_auth_type, mcpAuthLabel(authMethod)))
            }
            DropdownMenu(expanded = authExpanded, onDismissRequest = { authExpanded = false }) {
                listOf(McpAuthMethod.BEARER_TOKEN, McpAuthMethod.QUERY_PARAM, McpAuthMethod.OAUTH2, McpAuthMethod.NONE).forEach { method ->
                    DropdownMenuItem(
                        text = { Text(mcpAuthLabel(method)) },
                        onClick = {
                            onAuthMethodChange(method)
                            authExpanded = false
                        }
                    )
                }
            }
        }
        if (authMethod in listOf(McpAuthMethod.BEARER_TOKEN, McpAuthMethod.QUERY_PARAM)) {
            OutlinedTextField(
                value = authKey,
                onValueChange = onAuthKeyChange,
                label = { Text(if (authMethod == McpAuthMethod.BEARER_TOKEN) "Header key" else "Query parameter name") },
                isError = !authValid,
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (authMethod == McpAuthMethod.OAUTH2) {
            Text(stringResource(R.string.oauth2_authorization_endpoint), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = oauthAuthUrl,
                onValueChange = onOauthAuthUrlChange,
                placeholder = { Text("https://example.com/oauth/authorize") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { oauthAdvancedExpanded = !oauthAdvancedExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (oauthAdvancedExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.advanced_client_credentials),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (oauthAdvancedExpanded) {
                Text(stringResource(R.string.client_id), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = oauthClientId,
                    onValueChange = onOauthClientIdChange,
                    placeholder = { Text("multigateway") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(R.string.redirect_uri), style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = "multigateway://oauth",
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                    )
                )
            }

            val browserContext = LocalContext.current
            val scope = rememberCoroutineScope()
            var isDiscovering by remember { mutableStateOf(false) }

            Button(
                onClick = org.starfall.multigateway.ui.components.rememberOAuthStart {
                    scope.launch {
                        isDiscovering = true
                        var finalAuthUrl = oauthAuthUrl.trim()
                        
                        // If no auth URL is entered, perform automatic discovery handshake!
                        if (finalAuthUrl.isBlank() && url.isNotBlank()) {
                            try {
                                val client = okhttp3.OkHttpClient()
                                val request = okhttp3.Request.Builder().url(url).build()
                                withContext(Dispatchers.IO) {
                                    client.newCall(request).execute().use { response ->
                                        // 1. Check WWW-Authenticate header for OAuth2 endpoints
                                        val authHeader = response.header("WWW-Authenticate")
                                        if (authHeader != null) {
                                            val uriRegex = """authorization_uri="([^"]+)"""".toRegex()
                                            val match = uriRegex.find(authHeader)
                                            if (match != null) {
                                                finalAuthUrl = match.groupValues[1]
                                            }
                                        }
                                        // 2. Or check standard PRM / metadata if JSON is returned
                                        if (finalAuthUrl.isBlank()) {
                                            val body = response.body?.string()
                                            if (body != null && body.contains("authorization_endpoint")) {
                                                val json = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                                                finalAuthUrl = json["authorization_endpoint"]?.jsonPrimitive?.content.orEmpty()
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Fallback to a sensible default guess
                                finalAuthUrl = if (url.endsWith("/")) url + "oauth/authorize" else url + "/oauth/authorize"
                            }
                        }
                        
                        // If still blank, fallback to guess
                        if (finalAuthUrl.isBlank() && url.isNotBlank()) {
                            finalAuthUrl = if (url.endsWith("/")) url + "oauth/authorize" else url + "/oauth/authorize"
                        }
                        
                        isDiscovering = false
                        
                        if (finalAuthUrl.isNotBlank()) {
                            val computedUrl = android.net.Uri.parse(finalAuthUrl).buildUpon()
                                .appendQueryParameter("response_type", "token")
                                .appendQueryParameter("state", OAuthReceiver.begin())
                                .appendQueryParameter("client_id", oauthClientId.trim())
                                .appendQueryParameter("redirect_uri", "multigateway://oauth")
                                .build().toString()
                                
                            try {
                                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(computedUrl))
                                org.starfall.multigateway.data.adapter.common.OAuthCallbackService.start(browserContext)
                                browserContext.startActivity(intent)
                            } catch (e: Exception) {
                                org.starfall.multigateway.data.adapter.common.OAuthCallbackService.stop(browserContext)
                                android.widget.Toast.makeText(browserContext, browserContext.getString(R.string.cannot_open_browser, e.message.orEmpty()), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            android.widget.Toast.makeText(browserContext, browserContext.getString(R.string.enter_server_url_first), android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                },
                enabled = !isDiscovering && url.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isDiscovering) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.discovering_endpoint))
                } else {
                    Text(stringResource(R.string.authorize_in_browser))
                }
            }
        }

        if (authMethod != McpAuthMethod.NONE) {
            OutlinedTextField(
                value = authValue,
                onValueChange = onAuthValueChange,
                label = {
                    Text(
                        stringResource(
                            when (authMethod) {
                                McpAuthMethod.OAUTH2 -> R.string.oauth2_access_token
                                McpAuthMethod.BEARER_TOKEN -> R.string.bearer_token
                                else -> R.string.common_value
                            }
                        )
                    )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().onFocusChanged {
                    if (!it.isFocused && authMethod == McpAuthMethod.BEARER_TOKEN) {
                        onAuthValueChange(bearerHeaderValue(authKey.trim().ifBlank { "Authorization" }, authValue))
                    }
                }
            )
        }

        McpDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.custom_headers), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { onHeadersChange(headers + ("" to "")) }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_header))
            }
        }
        headers.forEachIndexed { index, header ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = header.first,
                    onValueChange = { value ->
                        onHeadersChange(headers.toMutableList().also { it[index] = value to header.second })
                    },
                    label = { Text(stringResource(R.string.common_key)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = header.second,
                    onValueChange = { value ->
                        onHeadersChange(headers.toMutableList().also { it[index] = header.first to value })
                    },
                    label = { Text(stringResource(R.string.common_value)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onHeadersChange(headers.filterIndexed { i, _ -> i != index }) }) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.remove_header))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

@Composable
private fun McpToolsTab(
    serverId: String,
    tools: List<ToolDefinition>?,
    loading: Boolean,
    error: String?,
    ready: Boolean,
    toolSettings: ToolSettings,
    onSetToolEnabled: (String, String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.available_tools), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (tools == null) stringResource(R.string.no_cached_tool_list) else stringResource(R.string.cached_tools_count, tools.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRefresh, enabled = ready && !loading) {
                Text(stringResource(if (loading) R.string.refreshing else R.string.refresh))
            }
        }

        if (loading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        if (!ready) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.mcp_enter_server_first),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
            return@Column
        }

        if (error != null) {
            Text(
                text = error.lineSequence().firstOrNull().orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        when {
            tools == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.no_cached_tools_yet),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
            tools.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.server_reported_no_tools),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(tools, key = { it.name }) { tool ->
                    McpToolItem(
                        tool = tool,
                        enabled = toolSettings.mcpTools[serverId]?.get(tool.originalName) != false,
                        onEnabledChange = { onSetToolEnabled(serverId, tool.originalName, it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun McpToolItem(
    tool: ToolDefinition,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    var expanded by remember(tool.name) { mutableStateOf(false) }
    val properties = tool.schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
    val required = (tool.schema["required"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.toSet()
        .orEmpty()

    Surface(
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                headlineContent = {
                    Text(tool.originalName, fontWeight = FontWeight.Medium)
                },
                supportingContent = {
                    if (tool.description.isNotBlank()) {
                        Text(
                            tool.description,
                            maxLines = if (expanded) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                leadingContent = {
                    Icon(Icons.Outlined.Extension, contentDescription = null)
                },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = enabled,
                            onCheckedChange = onEnabledChange
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight,
                            contentDescription = stringResource(if (expanded) R.string.collapse_tool_details else R.string.expand_tool_details)
                        )
                    }
                }
            )

            if (expanded) {
                HorizontalDivider()
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (tool.description.isNotBlank()) {
                        Text(stringResource(R.string.common_description), style = MaterialTheme.typography.labelLarge)
                        Text(
                            tool.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(stringResource(R.string.common_parameters), style = MaterialTheme.typography.labelLarge)

                    if (properties.isEmpty()) {
                        Text(
                            stringResource(R.string.no_parameters),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        properties.forEach { (name, rawSchema) ->
                            val schema = rawSchema as? JsonObject ?: JsonObject(emptyMap())
                            val type = schema["type"]?.jsonPrimitive?.contentOrNull
                                ?: if (schema["enum"] is JsonArray) "enum" else "any"
                            val description = schema["description"]?.jsonPrimitive?.contentOrNull
                            val enumValues = (schema["enum"] as? JsonArray)
                                ?.joinToString(", ") { value ->
                                    (value as? JsonPrimitive)?.contentOrNull ?: value.toString()
                                }

                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        type,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (name in required) {
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            "required",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                if (!description.isNullOrBlank()) {
                                    Text(
                                        description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (!enumValues.isNullOrBlank()) {
                                    Text(
                                        "Allowed: $enumValues",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun McpDivider() {
    HorizontalDivider()
}

@Composable
private fun McpTransportSelector(protocol: McpProtocol, onSelect: (McpProtocol) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(48.dp)) {
        McpTransportOption(
            label = "Streamable HTTP",
            selected = protocol == McpProtocol.STREAMABLE_HTTP,
            shape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp),
            modifier = Modifier.weight(1f),
            onClick = { onSelect(McpProtocol.STREAMABLE_HTTP) }
        )
        McpTransportOption(
            label = "SSE",
            selected = protocol == McpProtocol.SSE,
            shape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp),
            modifier = Modifier.weight(1f),
            onClick = { onSelect(McpProtocol.SSE) }
        )
    }
}

@Composable
private fun McpTransportOption(
    label: String,
    selected: Boolean,
    shape: RoundedCornerShape,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxHeight().clickable(onClick = onClick),
        shape = shape,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (selected) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(label, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
        }
    }
}

@Composable
private fun mcpAuthLabel(method: McpAuthMethod): String = stringResource(when (method) {
    McpAuthMethod.NONE -> R.string.common_none
    McpAuthMethod.BEARER_TOKEN -> R.string.bearer_token
    McpAuthMethod.QUERY_PARAM -> R.string.url_query
    McpAuthMethod.CUSTOM_HEADER -> R.string.bearer_token
    McpAuthMethod.OAUTH2 -> R.string.oauth_flow
})

@Composable
private fun mcpProtocolLabel(protocol: McpProtocol): String = stringResource(
    if (protocol == McpProtocol.STREAMABLE_HTTP) R.string.mcp_streamable_http else R.string.mcp_sse
)
