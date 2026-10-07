package org.starfall.multigateway.ui.providers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.multigateway.R
import org.starfall.multigateway.ui.components.AppDialog
import org.starfall.multigateway.ui.components.RoundedDropdownMenuItem
import org.starfall.multigateway.ui.components.SelectableOutlinedTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderApiKeysDialog(state: ProviderApiKeysState, identity: (String) -> String, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun edit(id: String?) {
        val entry = state.entries.firstOrNull { it.id == id }
        editingId = id; label = entry?.label.orEmpty(); value = entry?.value.orEmpty()
        revealed = false; error = null; editing = true
    }
    AppDialog(onDismissRequest = { if (editing) editing = false else onDismiss() }) {
        BackHandler(enabled = editing) { editing = false }
        Surface(shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Column {
                TopAppBar(
                    title = { Text(stringResource(if (editing) R.string.provider_edit_api_key else R.string.provider_manage_api_keys),
                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { if (editing) IconButton(onClick = { editing = false }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    } },
                    actions = {
                        if (editing) IconButton(enabled = value.isNotBlank(), onClick = {
                            runCatching { state.save(editingId, label, value, identity) }.onSuccess { editing = false }.onFailure { error = it.message }
                        }) { Icon(Icons.Outlined.Save, stringResource(R.string.provider_save_api_key)) }
                        else IconButton(onClick = { edit(null) }) { Icon(Icons.Default.Add, stringResource(R.string.provider_add_api_key)) }
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.common_close)) }
                    }
                )
                if (editing) Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SelectableOutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.provider_api_key_name)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth().testTag("provider_api_key_label"))
                    SelectableOutlinedTextField(value, { value = it; error = null }, label = { Text(stringResource(R.string.provider_api_key_value)) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                        visualTransformation = if (revealed) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = { IconButton(onClick = { revealed = !revealed }) {
                            Icon(if (revealed) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                                stringResource(if (revealed) R.string.provider_hide_api_key else R.string.provider_show_api_key))
                        } }, maxLines = 3, isError = error != null, modifier = Modifier.fillMaxWidth().testTag("provider_managed_api_key"))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                } else {
                    val selectedId = state.selectedId(identity)
                    val ordered = state.entries.sortedBy { it.id != selectedId }
                    if (ordered.isEmpty()) Text(stringResource(R.string.provider_no_api_keys), Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium)
                    else LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 12.dp)) {
                        items(ordered, key = { it.id }) { entry ->
                            val selected = entry.id == selectedId
                            var menu by remember(entry.id) { mutableStateOf(false) }
                            Surface(color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                                Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = {
                                    state.currentKey = entry.value; onDismiss()
                                }).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                                        Text(entry.label.ifBlank { maskedProviderApiKey(entry.value) }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (selected) Text(stringResource(R.string.provider_selected_api_key), style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    }
                                    if (selected) Icon(Icons.Default.Check, stringResource(R.string.provider_selected_api_key))
                                    Box {
                                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert,
                                            stringResource(R.string.provider_api_key_actions, entry.label.ifBlank { maskedProviderApiKey(entry.value) })) }
                                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                            RoundedDropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) },
                                                leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; edit(entry.id) })
                                            RoundedDropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) },
                                                leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; state.delete(entry.id, identity) })
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
}
