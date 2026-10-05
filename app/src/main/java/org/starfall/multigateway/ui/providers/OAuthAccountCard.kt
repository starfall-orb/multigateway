package org.starfall.multigateway.ui.providers
import org.starfall.multigateway.ui.components.RoundedDropdownMenuItem as DropdownMenuItem

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.starfall.multigateway.R

@Composable
internal fun OAuthAccountCard(
    signedIn: Boolean,
    identity: String?,
    signingIn: Boolean,
    signingOut: Boolean,
    canSignIn: Boolean,
    canSignOut: Boolean,
    canCancelSignIn: Boolean,
    error: String?,
    onSignIn: () -> Unit,
    onCancelSignIn: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val busy = signingIn || signingOut
    val status = stringResource(when {
        signingOut -> R.string.oauth_signing_out
        signingIn -> R.string.oauth_signing_in
        signedIn -> R.string.oauth_signed_in
        else -> R.string.oauth_signed_out
    })
    val accent = if (signedIn && !busy) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = if (signedIn && !busy) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHighest
                ) {
                    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                        if (busy) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(
                                if (signedIn) Icons.Outlined.CheckCircle else Icons.Outlined.AccountCircle,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(status, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, color = accent)
                    Text(
                        text = when {
                            signingOut -> stringResource(R.string.oauth_removing_account)
                            signingIn -> stringResource(R.string.oauth_browser_pending)
                            signedIn -> identity?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.oauth_account_connected)
                            else -> stringResource(R.string.oauth_browser_hint)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (signedIn) {
                    Box {
                        IconButton(onClick = { menuExpanded = true }, enabled = !busy) {
                            Icon(Icons.Outlined.MoreVert, stringResource(R.string.oauth_account_actions))
                        }
                        DropdownMenu(menuExpanded && !busy, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.oauth_sign_in_again)) },
                                leadingIcon = { Icon(Icons.Outlined.OpenInBrowser, null) },
                                enabled = canSignIn,
                                onClick = { menuExpanded = false; onSignIn() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.oauth_sign_out),
                                    color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Logout, null,
                                    tint = MaterialTheme.colorScheme.error) },
                                enabled = canSignOut,
                                onClick = { menuExpanded = false; onSignOut() }
                            )
                        }
                    }
                }
            }
            if (!signedIn && !busy) {
                Button(onClick = onSignIn, enabled = canSignIn, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.OpenInBrowser, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.oauth_sign_in))
                }
            }
            if (signingIn) {
                OutlinedButton(onClick = onCancelSignIn, enabled = canCancelSignIn, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
