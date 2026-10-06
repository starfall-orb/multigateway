package org.starfall.multigateway.ui.drawer

import org.starfall.multigateway.ui.components.AppDialog as Dialog
import org.starfall.multigateway.ui.components.SelectableOutlinedTextField

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.components.windowHeightIn
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptLibraryDialog(initialLibrary: PromptLibrary, onChange: (PromptLibrary) -> Unit, onDismiss: () -> Unit) {
    var library by remember { mutableStateOf(initialLibrary) }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<SavedPrompt?>(null) }
    fun update(next: PromptLibrary) { library = next; onChange(next) }
    val showEditor = creating || editing != null
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().windowHeightIn(maxFraction = 0.85f).padding(16.dp)) {
                if (showEditor) {
                    var name by remember(editing?.id) { mutableStateOf(editing?.name.orEmpty()) }
                    var content by remember(editing?.id) { mutableStateOf(editing?.content.orEmpty()) }
                    var role by remember(editing?.id) { mutableStateOf(editing?.role ?: ChatRole.SYSTEM) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { creating = false; editing = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to prompt list")
                        }
                        Text(if (creating) "New prompt" else "Edit prompt", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconButton(enabled = name.isNotBlank() && content.isNotBlank(), onClick = {
                            val id = editing?.id ?: UUID.randomUUID().toString()
                            val prompt = SavedPrompt(id, name.trim(), content.trim(), role)
                            update(library.copy(
                                prompts = if (editing == null) library.prompts + prompt else library.prompts.map { if (it.id == id) prompt else it },
                                selectedIds = if (editing == null) library.selectedIds + id else library.selectedIds))
                            creating = false; editing = null
                        }) { Icon(Icons.Outlined.Save, "Save prompt") }
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close prompts") }
                    }
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SelectableOutlinedTextField(name, { name = it }, label = { Text("Prompt name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        val roles = listOf(ChatRole.SYSTEM, ChatRole.USER, ChatRole.MODEL)
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            roles.forEachIndexed { index, value ->
                                SegmentedButton(selected = role == value, onClick = { role = value },
                                    shape = SegmentedButtonDefaults.itemShape(index, roles.size)) { Text(promptRoleName(value)) }
                            }
                        }
                        SelectableOutlinedTextField(content, { content = it }, label = { Text("Prompt") },
                            minLines = 6, maxLines = 12, modifier = Modifier.fillMaxWidth())
                    }
                } else {
                    val allSelected = library.prompts.isNotEmpty() && library.prompts.all { it.id in library.selectedIds }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("System prompts", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, "Add prompt") }
                        IconButton(enabled = library.prompts.isNotEmpty(), onClick = {
                            update(library.copy(selectedIds = if (allSelected) emptySet() else library.prompts.map { it.id }.toSet()))
                        }) { Icon(if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll,
                            if (allSelected) "Deselect all prompts" else "Select all prompts") }
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close prompts") }
                    }
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(top = 8.dp)) {
                        if (library.prompts.isEmpty()) item { Text("No saved prompts", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.outline) }
                        items(library.prompts, key = { it.id }) { prompt ->
                            val selected = prompt.id in library.selectedIds
                            Surface(shape = MaterialTheme.shapes.medium,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f) else MaterialTheme.colorScheme.surfaceContainerLow,
                                modifier = Modifier.fillMaxWidth().toggleable(selected, role = Role.Checkbox, onValueChange = {
                                    update(library.copy(selectedIds = if (selected) library.selectedIds - prompt.id else library.selectedIds + prompt.id))
                                })) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(selected, onCheckedChange = null)
                                    Column(Modifier.weight(1f)) {
                                        Text(prompt.name, style = MaterialTheme.typography.titleSmall)
                                        Text(promptRoleName(prompt.role), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                                        Text(prompt.content, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Column {
                                        IconButton(onClick = { editing = prompt }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.Edit, "Edit ${prompt.name}") }
                                        IconButton(onClick = { update(library.copy(prompts = library.prompts.filterNot { it.id == prompt.id },
                                            selectedIds = library.selectedIds - prompt.id)) }, modifier = Modifier.size(36.dp)) {
                                            Icon(Icons.Outlined.Delete, "Delete ${prompt.name}")
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

private fun promptRoleName(role: ChatRole): String = when (role) {
    ChatRole.SYSTEM -> "System"
    ChatRole.USER -> "User"
    ChatRole.MODEL -> "Assistant"
}
