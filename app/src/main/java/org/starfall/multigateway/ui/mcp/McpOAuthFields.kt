package org.starfall.multigateway.ui.mcp
import org.starfall.multigateway.ui.components.SelectableOutlinedTextField

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.starfall.multigateway.R
import org.starfall.multigateway.data.service.McpOAuthService
import org.starfall.multigateway.ui.components.rememberOAuthStart

@Composable
internal fun McpOAuthFields(url: String, clientId: String, onClientIdChange: (String) -> Unit,
    clientSecret: String, onClientSecretChange: (String) -> Unit, authorized: Boolean,
    authorize: suspend () -> Result<Unit>, clear: suspend () -> Unit, advanced: Boolean, onToggleAdvanced: () -> Unit) {
    Text(stringResource(R.string.mcp_oauth_discovery_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggleAdvanced).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(if (advanced) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.advanced_client_credentials), style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.primary)
    }
    if (advanced) {
        Text(stringResource(R.string.client_id), style = MaterialTheme.typography.titleSmall)
        SelectableOutlinedTextField(clientId, onClientIdChange, placeholder = { Text(stringResource(R.string.mcp_oauth_client_id_optional)) },
            supportingText = { Text(stringResource(R.string.mcp_oauth_client_id_help)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        SelectableOutlinedTextField(clientSecret, onClientSecretChange, label = { Text(stringResource(R.string.client_secret)) },
            supportingText = { Text(stringResource(R.string.mcp_oauth_client_secret_help)) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.redirect_uri), style = MaterialTheme.typography.titleSmall)
        SelectableOutlinedTextField(McpOAuthService.REDIRECT_URI, {}, readOnly = true, singleLine = true, modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant))
    }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var authorizeJob by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    Text(stringResource(if (authorized) R.string.mcp_oauth_authorized else R.string.mcp_oauth_not_authorized),
        style = MaterialTheme.typography.bodySmall, color = if (authorized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = rememberOAuthStart {
        authorizeJob = scope.launch {
            busy = true; error = null
            try {
                authorize().onFailure { error = it.message ?: it.toString() }
            } catch (_: CancellationException) {
            } finally {
                busy = false
                authorizeJob = null
            }
        }
    }, enabled = !busy && url.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        if (busy) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(stringResource(if (authorized) R.string.mcp_reauthorize_oauth else R.string.authorize_in_browser))
    }
    if (authorizeJob?.isActive == true) {
        OutlinedButton(
            onClick = { authorizeJob?.cancel(CancellationException("OAuth authorization cancelled by user")) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.common_cancel))
        }
    }
    if (authorized) OutlinedButton(onClick = {
        scope.launch { busy = true; error = null; try { clear() } finally { busy = false } }
    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.mcp_remove_oauth)) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}
