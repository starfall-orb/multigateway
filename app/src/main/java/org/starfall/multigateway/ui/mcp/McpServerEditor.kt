package org.starfall.multigateway.ui.mcp

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.starfall.multigateway.R
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.navigation.LocalScreenTransitionActive

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddOrEditMcpScreenContent(
    initialServer: McpInfo, isNew: Boolean,
    onRefreshTools: suspend (McpInfo) -> Result<List<ToolDefinition>>,
    onAuthorizeOAuth: suspend (McpInfo) -> Result<McpInfo>, onClearOAuth: suspend (McpInfo) -> McpInfo,
    cachedTools: List<ToolDefinition>?, cachedError: String?, cachedLoading: Boolean,
    toolSettings: ToolSettings, onSetToolEnabled: (String, String, Boolean) -> Unit,
    onDismiss: () -> Unit, onSave: (McpInfo) -> Unit
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
    var oauthClientId by remember(initialServer.id) { mutableStateOf(initialServer.auth.oauthClientId.orEmpty()) }
    var oauthClientSecret by remember(initialServer.id) { mutableStateOf(initialServer.auth.oauthClientSecret.orEmpty()) }
    var oauthAuthorized by remember(initialServer.id) { mutableStateOf(initialServer.auth.oauthAuthorized ||
        (initialServer.auth.method == McpAuthMethod.OAUTH2 && initialServer.auth.value?.isNotBlank() == true)) }

    fun currentAuth(): McpAuthorization = when (authMethod) {
        McpAuthMethod.NONE -> McpAuthorization()
        McpAuthMethod.OAUTH2 -> McpAuthorization(method = McpAuthMethod.OAUTH2,
            value = authValue.trim().takeIf { it.isNotEmpty() }, oauthClientId = oauthClientId.trim().takeIf { it.isNotEmpty() },
            oauthClientSecret = oauthClientSecret.takeIf { it.isNotBlank() }, oauthAuthorized = oauthAuthorized)
        McpAuthMethod.BEARER_TOKEN -> McpAuthorization(method = authMethod, key = authKey.trim(),
            value = bearerHeaderValue(authKey.ifBlank { "Authorization" }, authValue))
        McpAuthMethod.QUERY_PARAM -> McpAuthorization(method = authMethod, key = authKey.trim(), value = authValue.trim())
        McpAuthMethod.CUSTOM_HEADER -> McpAuthorization(method = McpAuthMethod.BEARER_TOKEN, key = authKey.trim(), value = authValue.trim())
    }
    fun currentServer() = initialServer.copy(name = name.trim(), icon = serverIcon, protocol = protocol,
        url = url.trim().ifEmpty { null }, headers = headers.map { it.first.trim() to it.second }
            .filter { it.first.isNotEmpty() }.toMap().ifEmpty { null }, auth = currentAuth())
    val authValid = authValue.isBlank() || authKey.isBlank() || authMethod !in listOf(McpAuthMethod.BEARER_TOKEN, McpAuthMethod.QUERY_PARAM) ||
        (authKey.isNotBlank() && !authKey.contains(':') && authKey.none { it <= ' ' || it.code >= 127 })
    val canSave = name.isNotBlank() && (url.isNotBlank() || name.isContentApiName()) && authValid && headers.all {
        it.first.isNotBlank() && !it.first.contains(':') && !it.first.any { ch -> ch == '\r' || ch == '\n' } &&
            !it.second.any { ch -> ch == '\r' || ch == '\n' }
    }
    fun refreshTools() { if (canSave && !cachedLoading) scope.launch { onRefreshTools(currentServer()) } }
    BackHandler(enabled = LocalScreenTransitionActive.current, onBack = onDismiss)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                Column(Modifier.statusBarsPadding()) {
                    TopAppBar(windowInsets = WindowInsets(0, 0, 0, 0),
                        title = { Text(stringResource(if (isNew) R.string.add_mcp_server_title else R.string.configure_mcp_server_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)) },
                        navigationIcon = { IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                        } },
                        actions = { Button(onClick = { onSave(currentServer()) }, enabled = canSave && !iconImporting,
                            modifier = Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.common_save)) } })
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selectedTab == 0, { selectedTab = 0 }, text = { Text(stringResource(R.string.mcp_basic_settings), fontWeight = FontWeight.SemiBold) })
                    Tab(selectedTab == 1, { selectedTab = 1 }, text = { Text(stringResource(R.string.mcp_tools), fontWeight = FontWeight.SemiBold) })
                }
                if (selectedTab == 0) McpConnectionSettings(
                    name = name, icon = serverIcon, onIconChange = { serverIcon = it }, onIconBusyChange = { iconImporting = it }, onNameChange = { name = it },
                    protocol = protocol, onProtocolChange = { protocol = it }, url = url,
                    onUrlChange = { changed -> if (authMethod == McpAuthMethod.OAUTH2 && changed.trim() != url.trim()) oauthAuthorized = false; url = changed },
                    authMethod = authMethod, onAuthMethodChange = { method ->
                        if (method != authMethod) { authValue = ""; if (method == McpAuthMethod.OAUTH2) oauthAuthorized = false }
                        authMethod = method; authKey = if (method == McpAuthMethod.QUERY_PARAM) "key" else "Authorization"
                    }, authKey = authKey, onAuthKeyChange = { authKey = it }, authValue = authValue, onAuthValueChange = { authValue = it }, authValid = authValid,
                    headers = headers, onHeadersChange = { headers = it }, oauthClientId = oauthClientId,
                    onOauthClientIdChange = { changed -> if (changed.trim() != oauthClientId.trim()) oauthAuthorized = false; oauthClientId = changed },
                    oauthClientSecret = oauthClientSecret,
                    onOauthClientSecretChange = { changed -> if (changed != oauthClientSecret) oauthAuthorized = false; oauthClientSecret = changed },
                    oauthAuthorized = oauthAuthorized, onAuthorizeOAuth = {
                        onAuthorizeOAuth(currentServer()).map { authorized ->
                            oauthClientId = authorized.auth.oauthClientId.orEmpty(); oauthClientSecret = authorized.auth.oauthClientSecret.orEmpty()
                            oauthAuthorized = authorized.auth.oauthAuthorized; authValue = authorized.auth.value.orEmpty()
                        }
                    }, onClearOAuth = {
                        val cleared = onClearOAuth(currentServer())
                        oauthClientId = cleared.auth.oauthClientId.orEmpty(); oauthClientSecret = cleared.auth.oauthClientSecret.orEmpty()
                        oauthAuthorized = false; authValue = cleared.auth.value.orEmpty()
                    }, modifier = Modifier.weight(1f)
                ) else McpToolsTab(initialServer.id, cachedTools, cachedLoading, cachedError, canSave, toolSettings,
                    onSetToolEnabled, ::refreshTools, Modifier.weight(1f))
            }
        }
    }
}
