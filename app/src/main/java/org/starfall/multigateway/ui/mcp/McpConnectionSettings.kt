package org.starfall.multigateway.ui.mcp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.starfall.multigateway.R
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.components.IconPickerRow
import org.starfall.multigateway.ui.components.RoundedDropdownMenuItem

@Composable
internal fun mcpAuthenticationLabel(method: McpAuthMethod) = stringResource(when (method) {
    McpAuthMethod.NONE -> R.string.common_none
    McpAuthMethod.BEARER_TOKEN, McpAuthMethod.CUSTOM_HEADER -> R.string.bearer_token
    McpAuthMethod.QUERY_PARAM -> R.string.url_query
    McpAuthMethod.OAUTH2 -> R.string.oauth_flow
})

@Composable
internal fun mcpTransportLabel(protocol: McpProtocol) = stringResource(
    if (protocol == McpProtocol.STREAMABLE_HTTP) R.string.mcp_streamable_http else R.string.mcp_sse)

@Composable
internal fun McpConnectionSettings(
    name: String, icon: String?, onIconChange: (String?) -> Unit, onIconBusyChange: (Boolean) -> Unit, onNameChange: (String) -> Unit,
    protocol: McpProtocol, onProtocolChange: (McpProtocol) -> Unit, url: String, onUrlChange: (String) -> Unit,
    authMethod: McpAuthMethod, onAuthMethodChange: (McpAuthMethod) -> Unit,
    authKey: String, onAuthKeyChange: (String) -> Unit, authValue: String, onAuthValueChange: (String) -> Unit, authValid: Boolean,
    headers: List<Pair<String, String>>, onHeadersChange: (List<Pair<String, String>>) -> Unit,
    oauthClientId: String, onOauthClientIdChange: (String) -> Unit,
    oauthClientSecret: String, onOauthClientSecretChange: (String) -> Unit,
    oauthAuthorized: Boolean, onAuthorizeOAuth: suspend () -> Result<Unit>, onClearOAuth: suspend () -> Unit,
    modifier: Modifier = Modifier
) {
    var oauthAdvancedExpanded by remember { mutableStateOf(false) }
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        IconPickerRow(icon, onIconChange, fallback = Icons.Outlined.Extension, onBusyChange = onIconBusyChange, matchName = name)
        Text(stringResource(R.string.display_name), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(name, onNameChange, placeholder = { Text(stringResource(R.string.common_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.legacy_sse), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(protocol == McpProtocol.SSE, onCheckedChange = { onProtocolChange(if (it) McpProtocol.SSE else McpProtocol.STREAMABLE_HTTP) })
        }
        HorizontalDivider()
        Text(stringResource(R.string.server_url), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.server_url_help, mcpTransportLabel(protocol)), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(url, onUrlChange, placeholder = { Text(stringResource(R.string.common_url)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (url.trim().startsWith("http://", true)) Text(stringResource(R.string.http_unencrypted_mcp_warning),
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.mcp_auth), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.mcp_auth_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedButton(onClick = { expanded = true }) { Text(stringResource(R.string.mcp_auth_type, mcpAuthenticationLabel(authMethod))) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                listOf(McpAuthMethod.BEARER_TOKEN, McpAuthMethod.QUERY_PARAM, McpAuthMethod.OAUTH2, McpAuthMethod.NONE).forEach { method ->
                    RoundedDropdownMenuItem(text = { Text(mcpAuthenticationLabel(method)) }, onClick = { onAuthMethodChange(method); expanded = false })
                }
            }
        }
        if (authMethod in listOf(McpAuthMethod.BEARER_TOKEN, McpAuthMethod.QUERY_PARAM)) OutlinedTextField(authKey, onAuthKeyChange,
            label = { Text(if (authMethod == McpAuthMethod.BEARER_TOKEN) "Header key" else "Query parameter name") },
            isError = !authValid, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (authMethod == McpAuthMethod.OAUTH2) McpOAuthFields(url, oauthClientId, onOauthClientIdChange,
            oauthClientSecret, onOauthClientSecretChange, oauthAuthorized, onAuthorizeOAuth, onClearOAuth,
            oauthAdvancedExpanded, { oauthAdvancedExpanded = !oauthAdvancedExpanded })
        if (authMethod != McpAuthMethod.NONE && authMethod != McpAuthMethod.OAUTH2) OutlinedTextField(authValue, onAuthValueChange,
            label = { Text(stringResource(if (authMethod == McpAuthMethod.BEARER_TOKEN) R.string.bearer_token else R.string.common_value)) },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().onFocusChanged {
                if (!it.isFocused && authMethod == McpAuthMethod.BEARER_TOKEN) onAuthValueChange(bearerHeaderValue(authKey.trim().ifBlank { "Authorization" }, authValue))
            })
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.custom_headers), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = { onHeadersChange(headers + ("" to "")) }) { Icon(Icons.Default.Add, stringResource(R.string.add_header)) }
        }
        headers.forEachIndexed { index, header ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(header.first, { value -> onHeadersChange(headers.toMutableList().also { it[index] = value to header.second }) },
                    label = { Text(stringResource(R.string.common_key)) }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(header.second, { value -> onHeadersChange(headers.toMutableList().also { it[index] = header.first to value }) },
                    label = { Text(stringResource(R.string.common_value)) }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { onHeadersChange(headers.filterIndexed { i, _ -> i != index }) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.remove_header)) }
            }
        }
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.navigationBarsPadding())
    }
}
