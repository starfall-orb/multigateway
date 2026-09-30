@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package org.starfall.multigateway.ui.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.starfall.multigateway.data.model.*
import java.util.UUID

@Composable
fun ConversationsDrawer(
    conversations: List<Conversation>,
    currentConversationId: String?,
    isOpen: Boolean,
    generatingConversationId: String? = null,
    organization: SidebarOrganization,
    onUpdateOrganization: ((SidebarOrganization) -> SidebarOrganization) -> Unit,
    onDeleteConversations: (Set<String>) -> Unit,
    defaultSystemPrompt: String,
    onSelectConversation: (Conversation) -> Unit,
    onNewChat: () -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onUpdateDefaultSystemPrompt: (String) -> Unit,
    onNavigateToSettings: () -> Unit,
    onOpenMenu: () -> Unit,
    onCloseDrawer: () -> Unit,
    modifier: Modifier = Modifier
) {
    var search by remember { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var deleting by remember { mutableStateOf(emptySet<String>()) }
    var renaming by remember { mutableStateOf<Conversation?>(null) }
    var title by remember { mutableStateOf("") }
    var grouping by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("") }
    var editingFolder by remember { mutableStateOf<ChatFolder?>(null) }
    var deletingFolder by remember { mutableStateOf<ChatFolder?>(null) }
    var promptDialog by remember { mutableStateOf(false) }
    val folderIds = organization.folders.map { it.id }.toSet()
    val matches = remember(conversations, search, organization.folders, organization.chatFolders) {
        val matchingFolders = organization.folders.filter { search.isNotBlank() && it.name.contains(search, true) }.map { it.id }.toSet()
        conversations.filter { search.isBlank() || it.title.contains(search, true) || organization.chatFolders[it.id] in matchingFolders }
    }
    val matchingIds = matches.map { it.id }.toSet()
    fun stopSelecting() { selecting = false; selected = emptySet() }
    fun toggle(id: String) { selected = if (id in selected) selected - id else selected + id }
    fun moveToFolder(folderId: String?) {
        val ids = selected
        onUpdateOrganization { it.moveChats(ids, folderId) }
        grouping = false
        stopSelecting()
    }
    LaunchedEffect(conversations) { selected = selected.intersect(conversations.map { it.id }.toSet()) }
    LaunchedEffect(isOpen) { if (!isOpen) stopSelecting() }
    BackHandler(enabled = selecting && isOpen) { stopSelecting() }

    ModalDrawerSheet(modifier = modifier.width(320.dp), drawerContainerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true,
                placeholder = { Text("Search history...") },
                leadingIcon = { Icon(Icons.Default.Search, "Search") },
                trailingIcon = { if (search.isNotEmpty()) IconButton(onClick = { search = "" }) { Icon(Icons.Default.Close, "Clear search") } },
                shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(12.dp)
            )
            if (selecting) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::stopSelecting) { Icon(Icons.Default.Close, "Exit selection") }
                    Text("${selected.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    val allSelected = matchingIds.isNotEmpty() && selected.containsAll(matchingIds)
                    IconButton(enabled = matchingIds.isNotEmpty(), onClick = {
                        selected = if (allSelected) selected - matchingIds else selected + matchingIds
                    }) { Icon(if (allSelected) Icons.Outlined.Deselect else Icons.Outlined.SelectAll, if (allSelected) "Deselect all" else "Select all") }
                    IconButton(enabled = selected.isNotEmpty(), onClick = { folderName = ""; grouping = true }) { Icon(Icons.Outlined.CreateNewFolder, "Move selected chats to folder") }
                    IconButton(enabled = selected.isNotEmpty(), onClick = { deleting = selected }) { Icon(Icons.Outlined.Delete, "Delete selected chats", tint = MaterialTheme.colorScheme.error) }
                }
            }
            HorizontalDivider()
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val folders = organization.folders.sortedByDescending { it.pinned }
                if (folders.isNotEmpty()) item(key = "folders-label") { SidebarLabel("Folders") }
                folders.forEach { folder ->
                    val chats = matches.filter { organization.chatFolders[it.id] == folder.id }
                        .sortedWith(compareByDescending<Conversation> { it.id in organization.pinnedChatIds }.thenByDescending { it.updatedAt })
                    if (search.isBlank() || chats.isNotEmpty() || folder.name.contains(search, true)) {
                        item(key = "folder:${folder.id}") {
                            var menu by remember { mutableStateOf(false) }
                            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                                Row(Modifier.fillMaxWidth().combinedClickable(onClick = {
                                    onUpdateOrganization { state -> state.copy(folders = state.folders.map { if (it.id == folder.id) it.copy(expanded = !it.expanded) else it }) }
                                }).padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (folder.expanded || search.isNotBlank()) Icons.Default.ExpandMore else Icons.Default.ChevronRight, "Toggle folder")
                                    Icon(Icons.Outlined.Folder, null, modifier = Modifier.size(20.dp))
                                    Text(folder.name, Modifier.weight(1f).padding(8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${chats.size}", style = MaterialTheme.typography.labelSmall)
                                    if (folder.pinned) Icon(Icons.Default.PushPin, "Pinned folder", Modifier.size(14.dp))
                                    Box {
                                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Folder options") }
                                        DropdownMenu(menu, { menu = false }) {
                                            DropdownMenuItem(text = { Text(if (folder.pinned) "Unpin" else "Pin") }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) }, onClick = {
                                                menu = false
                                                onUpdateOrganization { state -> state.copy(folders = state.folders.map { if (it.id == folder.id) it.copy(pinned = !it.pinned) else it }) }
                                            })
                                            DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; editingFolder = folder; folderName = folder.name })
                                            DropdownMenuItem(text = { Text("Remove folder") }, onClick = { menu = false; deletingFolder = folder })
                                        }
                                    }
                                }
                            }
                        }
                        if (folder.expanded || search.isNotBlank()) {
                            items(chats, key = { "chat:${it.id}" }) { chat ->
                                DrawerChatRow(chat, currentConversationId, generatingConversationId, selecting, chat.id in selected, chat.id in organization.pinnedChatIds,
                                    onClick = { if (selecting) toggle(chat.id) else { onSelectConversation(chat); onCloseDrawer() } },
                                    onLongClick = { selecting = true; toggle(chat.id) },
                                    onPin = { onUpdateOrganization { it.copy(pinnedChatIds = if (chat.id in it.pinnedChatIds) it.pinnedChatIds - chat.id else it.pinnedChatIds + chat.id) } },
                                    onRename = { renaming = chat; title = chat.title }, onDelete = { deleting = setOf(chat.id) },
                                    onMove = { selecting = true; selected = setOf(chat.id); folderName = ""; grouping = true }, modifier = Modifier.padding(start = 14.dp))
                            }
                        }
                    }
                }
                item(key = "history-label") { SidebarLabel("History") }
                val history = matches.filter { organization.chatFolders[it.id] !in folderIds }
                    .sortedWith(compareByDescending<Conversation> { it.id in organization.pinnedChatIds }.thenByDescending { it.updatedAt })
                items(history, key = { "chat:${it.id}" }) { chat ->
                    DrawerChatRow(chat, currentConversationId, generatingConversationId, selecting, chat.id in selected, chat.id in organization.pinnedChatIds,
                        onClick = { if (selecting) toggle(chat.id) else { onSelectConversation(chat); onCloseDrawer() } },
                        onLongClick = { selecting = true; toggle(chat.id) },
                        onPin = { onUpdateOrganization { it.copy(pinnedChatIds = if (chat.id in it.pinnedChatIds) it.pinnedChatIds - chat.id else it.pinnedChatIds + chat.id) } },
                        onRename = { renaming = chat; title = chat.title }, onDelete = { deleting = setOf(chat.id) },
                        onMove = { selecting = true; selected = setOf(chat.id); folderName = ""; grouping = true })
                }
                if (matches.isEmpty()) item { Text(if (search.isBlank()) "No conversations yet" else "No matching conversations", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.outline) }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { stopSelecting(); onNewChat(); onCloseDrawer() }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New Chat", maxLines = 1)
                }
                IconButton(onClick = { promptDialog = true }) { Icon(Icons.Outlined.EditNote, "Default system prompt") }
                IconButton(onClick = { onNavigateToSettings(); onCloseDrawer() }) { Icon(Icons.Outlined.Settings, "Settings") }
                IconButton(onClick = onOpenMenu) { Icon(Icons.Outlined.Menu, "Menu") }
            }
        }
    }
    if (grouping) AlertDialog(
        onDismissRequest = { grouping = false }, title = { Text("Move to folder") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(organization.folders, key = { it.id }) { folder -> TextButton(onClick = { moveToFolder(folder.id) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Folder, null); Spacer(Modifier.width(8.dp)); Text(folder.name) } }
                item { TextButton(onClick = { moveToFolder(null) }) { Text("Move to History") } }
                item { OutlinedTextField(folderName, { folderName = it }, label = { Text("New folder name") }, singleLine = true) }
            }
        },
        confirmButton = { TextButton(enabled = folderName.isNotBlank(), onClick = {
            val name = folderName.trim(); val ids = selected; val id = UUID.randomUUID().toString()
            onUpdateOrganization { it.copy(folders = it.folders + ChatFolder(id, name)).moveChats(ids, id) }
            grouping = false; stopSelecting()
        }) { Text("Create folder") } }, dismissButton = { TextButton(onClick = { grouping = false }) { Text("Cancel") } }
    )
    if (deleting.isNotEmpty()) AlertDialog(onDismissRequest = { deleting = emptySet() }, title = { Text("Delete chats?") },
        text = { Text("Delete ${deleting.size} conversation(s) and all their messages?") },
        confirmButton = { TextButton(onClick = { val ids = deleting; deleting = emptySet(); if (ids.size == 1) onDeleteConversation(ids.first()) else onDeleteConversations(ids); stopSelecting() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleting = emptySet() }) { Text("Cancel") } })
    if (renaming != null) AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename chat") },
        text = { OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Chat title") }) },
        confirmButton = { TextButton(enabled = title.isNotBlank(), onClick = { renaming?.let { onRenameConversation(it.id, title.trim()) }; renaming = null }) { Text("Save") } }, dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    if (editingFolder != null) AlertDialog(onDismissRequest = { editingFolder = null }, title = { Text("Rename folder") },
        text = { OutlinedTextField(folderName, { folderName = it }, singleLine = true) },
        confirmButton = { TextButton(enabled = folderName.isNotBlank(), onClick = { val id = editingFolder!!.id; val name = folderName.trim(); onUpdateOrganization { state -> state.copy(folders = state.folders.map { if (it.id == id) it.copy(name = name) else it }) }; editingFolder = null }) { Text("Save") } }, dismissButton = { TextButton(onClick = { editingFolder = null }) { Text("Cancel") } })
    if (deletingFolder != null) AlertDialog(onDismissRequest = { deletingFolder = null }, title = { Text("Remove folder?") }, text = { Text("Its chats will return to History.") },
        confirmButton = { TextButton(onClick = { val id = deletingFolder!!.id; onUpdateOrganization { it.removeFolder(id) }; deletingFolder = null }) { Text("Remove") } }, dismissButton = { TextButton(onClick = { deletingFolder = null }) { Text("Cancel") } })
    if (promptDialog) {
        var prompt by remember(defaultSystemPrompt) { mutableStateOf(defaultSystemPrompt) }
        AlertDialog(onDismissRequest = { promptDialog = false }, title = { Text("Default system prompt") },
            text = { OutlinedTextField(prompt, { prompt = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp), label = { Text("System prompt") }) },
            confirmButton = { TextButton(onClick = { onUpdateDefaultSystemPrompt(prompt.trim()); promptDialog = false }) { Text("Save") } }, dismissButton = { TextButton(onClick = { promptDialog = false }) { Text("Cancel") } })
    }
}

@Composable
private fun SidebarLabel(text: String) { Text(text, Modifier.padding(start = 8.dp, top = 10.dp, bottom = 6.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }

@Composable
private fun DrawerChatRow(
    chat: Conversation, currentId: String?, generatingId: String?, selecting: Boolean, checked: Boolean, pinned: Boolean,
    onClick: () -> Unit, onLongClick: () -> Unit, onPin: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, onMove: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menu by remember { mutableStateOf(false) }
    val active = chat.id == currentId
    Surface(shape = RoundedCornerShape(12.dp), color = if (checked || active) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent,
        modifier = modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.padding(start = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(checked, onCheckedChange = { onClick() }, modifier = Modifier.size(36.dp)) else Box(Modifier.size(36.dp).clip(CircleShape).background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                Text(chat.title.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "#", color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(chat.title.ifBlank { "New Chat" }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                Text(formatTimestamp(chat.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            }
            if (pinned) Icon(Icons.Default.PushPin, "Pinned chat", Modifier.size(14.dp))
            if (chat.id == generatingId) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.5.dp)
            if (!selecting) Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, "Chat options", Modifier.size(18.dp)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (pinned) "Unpin" else "Pin") }, leadingIcon = { Icon(Icons.Outlined.PushPin, null) }, onClick = { menu = false; onPin() })
                    DropdownMenuItem(text = { Text("Move to folder") }, leadingIcon = { Icon(Icons.Outlined.Folder, null) }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Outlined.Edit, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete", color = MaterialTheme.colorScheme.error) }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val minutes = ((System.currentTimeMillis() - timestamp) / 60000).coerceAtLeast(0)
    return when { minutes < 1 -> "Just now"; minutes < 60 -> "${minutes}m ago"; minutes < 1440 -> "${minutes / 60}h ago"; minutes < 10080 -> "${minutes / 1440}d ago"; else -> "${minutes / 10080}w ago" }
}
