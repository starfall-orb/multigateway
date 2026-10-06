package org.starfall.multigateway.ui.providers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.starfall.multigateway.R
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderOAuthAccountsDialog(
    accounts: List<ProviderOAuthAccount>, selectedId: String?, canAuthorize: Boolean, canDelete: Boolean,
    onSelect: (ProviderOAuthAccount) -> Unit, onSaveLabel: (String, String) -> Unit,
    onAuthorize: suspend (String?, String) -> Result<LlmProviderInfo>, onCancelAuthorization: () -> Unit,
    onDelete: suspend (String) -> Result<LlmProviderInfo>, onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val cancelled = stringResource(R.string.oauth_sign_in_cancelled)
    fun edit(account: ProviderOAuthAccount?) {
        editingId = account?.id; label = account?.label.orEmpty(); error = null; editing = true
    }
    fun dismiss() { if (busy) onCancelAuthorization(); onDismiss() }
    val signIn = rememberOAuthStart {
        scope.launch {
            busy = true; error = null
            try {
                onAuthorize(editingId, label).onSuccess { editing = false }.onFailure { error = it.message }
            } catch (_: CancellationException) { error = cancelled }
            finally { busy = false }
        }
    }
    AppDialog(onDismissRequest = { if (editing && !busy) editing = false else dismiss() }) {
        BackHandler(enabled = editing && !busy) { editing = false }
        Surface(shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).imePadding()) {
            Column {
                TopAppBar(title = { Text(stringResource(if (editing) {
                    if (editingId == null) R.string.provider_add_account else R.string.provider_edit_account
                } else R.string.provider_manage_accounts)) },
                    navigationIcon = { if (editing) IconButton(enabled = !busy, onClick = { editing = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    } },
                    actions = {
                        if (editing) IconButton(enabled = !busy && (editingId != null || canAuthorize), onClick = {
                            if (editingId == null) signIn() else { onSaveLabel(editingId!!, label); editing = false }
                        }) { Icon(Icons.Outlined.Save, stringResource(R.string.common_save)) }
                        if (!editing) IconButton(enabled = !busy && canAuthorize, onClick = { edit(null) }) {
                            Icon(Icons.Default.Add, stringResource(R.string.provider_add_account))
                        }
                        IconButton(onClick = ::dismiss) { Icon(Icons.Default.Close, stringResource(R.string.common_close)) }
                    })
                error?.let { Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                if (editing) Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SelectableOutlinedTextField(label, { label = it }, singleLine = true, enabled = !busy,
                        label = { Text(stringResource(R.string.provider_account_label)) },
                        modifier = Modifier.fillMaxWidth().testTag("oauth-account-label"))
                    accounts.firstOrNull { it.id == editingId }?.identity?.let { Text(it) }
                    Button(enabled = !busy && canAuthorize, onClick = signIn, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(if (busy) R.string.oauth_signing_in else if (editingId == null)
                            R.string.oauth_sign_in else R.string.oauth_sign_in_again))
                    }
                    if (busy) TextButton(onClick = onCancelAuthorization) { Text(stringResource(R.string.common_cancel)) }
                } else if (accounts.isEmpty()) Text(stringResource(R.string.provider_no_accounts), Modifier.padding(24.dp))
                else LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) {
                    items(accounts.sortedBy { it.id != selectedId }, key = { it.id }) { account ->
                        val selected = account.id == selectedId
                        var menu by remember(account.id) { mutableStateOf(false) }
                        Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                            Row(Modifier.fillMaxWidth().selectable(selected, enabled = !busy, role = Role.RadioButton,
                                onClick = { onSelect(account) }).padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                    Text(account.label.ifBlank { account.identity ?: "Account ${accounts.indexOf(account) + 1}" })
                                    if (account.label.isNotBlank() && !account.identity.isNullOrBlank()) Text(account.identity,
                                        style = MaterialTheme.typography.bodySmall)
                                    if (selected) Text(stringResource(R.string.provider_selected_account),
                                        style = MaterialTheme.typography.labelMedium)
                                }
                                if (selected) Icon(Icons.Default.Check, stringResource(R.string.provider_selected_account))
                                Box {
                                    IconButton(enabled = !busy, onClick = { menu = true }) {
                                        Icon(Icons.Default.MoreVert, stringResource(R.string.oauth_account_actions))
                                    }
                                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                        RoundedDropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) },
                                            onClick = { menu = false; edit(account) })
                                        RoundedDropdownMenuItem(enabled = canDelete, text = { Text(stringResource(R.string.common_delete)) },
                                            onClick = {
                                                menu = false
                                                scope.launch {
                                                    busy = true; error = null
                                                    try { onDelete(account.id).onFailure { error = it.message } }
                                                    finally { busy = false }
                                                }
                                            })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
