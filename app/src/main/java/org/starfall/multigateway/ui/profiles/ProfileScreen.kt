package org.starfall.multigateway.ui.profiles

import org.starfall.multigateway.data.model.McpInfo
import org.starfall.multigateway.data.model.McpAccess
import org.starfall.multigateway.data.model.ToolDefinition
import org.starfall.multigateway.data.model.ToolSettings
import org.starfall.multigateway.ui.tools.ProfileMcpPermissions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.grid.GridItemSpan
import org.starfall.multigateway.data.model.ChatProfile
import org.starfall.multigateway.data.model.LlmChatConfig
import java.util.UUID
import org.starfall.multigateway.ui.components.ItemOverflowMenu
import org.starfall.multigateway.ui.components.MorphingCardLayout
import org.starfall.multigateway.ui.components.longPressReorder
import org.starfall.multigateway.ui.components.moved

private data class ProfileEditor(val profile: ChatProfile?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    profiles: List<ChatProfile>,
    isGridView: Boolean = false,
    onToggleGridView: ((Boolean) -> Unit)? = null,
    mcpServers: List<McpInfo>,
    mcpToolsCache: Map<String, List<ToolDefinition>>,
    toolSettings: ToolSettings,
    selectedProfileId: String?,
    onSelectProfile: (String?) -> Unit,
    onSaveProfile: (ChatProfile) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onReorderProfiles: (List<String>) -> Unit,
    onBack: () -> Unit
) {
    var editor by remember { mutableStateOf<ProfileEditor?>(null) }
    var deletingProfileId by remember { mutableStateOf<String?>(null) }
    var orderedProfiles by remember(profiles) { mutableStateOf(profiles) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Chat Profiles",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Manage chat profiles",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onToggleGridView?.invoke(!isGridView) }) {
                        Icon(
                            imageVector = if (isGridView) Icons.Default.List else Icons.Default.GridView,
                            contentDescription = "Toggle View"
                        )
                    }
                    IconButton(
                        onClick = {
                            editor = ProfileEditor(null)
                        }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New Profile")
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
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (isGridView) 2 else 1),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(orderedProfiles, key = { _, item -> item.id }) { index, profile ->
                    val isSelected = profile.id == selectedProfileId
                    ProfileUnifiedCard(
                        profile = profile,
                        isGrid = isGridView,
                        isSelected = isSelected,
                        modifier = Modifier
                            .animateItem(
                                placementSpec = spring(
                                    dampingRatio = Spring.DampingRatioNoBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            )
                            .longPressReorder(
                                index = index,
                                itemCount = orderedProfiles.size,
                                columns = if (isGridView) 2 else 1,
                                onMove = { from, to -> orderedProfiles = orderedProfiles.moved(from, to) },
                                onDrop = { onReorderProfiles(orderedProfiles.map { it.id }) }
                            ),
                        onSelect = { onSelectProfile(profile.id) },
                        onEdit = {
                            editor = ProfileEditor(profile)
                        },
                        onDelete = { deletingProfileId = profile.id }
                    )
                }
            }
        }
    }

    editor?.let { page ->
        AddOrEditProfileDialog(
            profile = page.profile,
            mcpServers = mcpServers,
            mcpToolsCache = mcpToolsCache,
            toolSettings = toolSettings,
            onDismiss = { editor = null },
            onSave = {
                onSaveProfile(it)
                editor = null
            }
        )
    }

    if (deletingProfileId != null) {
        AlertDialog(
            onDismissRequest = { deletingProfileId = null },
            title = { Text("Delete Profile") },
            text = { Text("Are you sure you want to delete this profile?") },
            confirmButton = {
                Button(
                    onClick = {
                        deletingProfileId?.let { onDeleteProfile(it) }
                        deletingProfileId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingProfileId = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun ProfileUnifiedCard(
    profile: ChatProfile,
    isGrid: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val shapeCorner by animateDpAsState(
        targetValue = if (isGrid) 20.dp else 16.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "profileShapeCorner"
    )

    val initials = remember(profile.name) {
        profile.name
            .split(" ")
            .filter { it.isNotBlank() }
            .take(2)
            .map { it.first().uppercaseChar() }
            .joinToString("")
    }

    Surface(
        shape = RoundedCornerShape(shapeCorner),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(
            if (isSelected) 2.dp else 1.5.dp,
            if (isSelected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
    ) {
        MorphingCardLayout(
            isGrid = isGrid,
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            icon = {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = profile.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isSelected) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    text = "Active",
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = profile.config.systemPrompt.ifBlank { "No system prompt" },
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        )
    }
}

@Composable
fun AddOrEditProfileDialog(
    profile: ChatProfile?,
    mcpServers: List<McpInfo>,
    mcpToolsCache: Map<String, List<ToolDefinition>>,
    toolSettings: ToolSettings,
    onDismiss: () -> Unit,
    onSave: (ChatProfile) -> Unit
) {
    var name by remember { mutableStateOf(profile?.name ?: "") }
    var mcpAccess by remember {
        mutableStateOf(
            profile?.config?.mcpAccess ?: mcpServers.associate { server ->
                val cached = mcpToolsCache[server.id] ?: server.cachedTools.orEmpty()
                server.id to McpAccess(
                    enabled = true,
                    tools = cached.associate { it.originalName to true }
                )
            }
        )
    }
    var systemPrompt by remember { mutableStateOf(profile?.config?.systemPrompt ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) "New Profile" else "Edit Profile") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("System Prompt") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
                ProfileMcpPermissions(
                    servers = mcpServers,
                    access = mcpAccess,
                    toolsCache = mcpToolsCache,
                    settings = toolSettings
                ) { mcpAccess = it }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    if (name.isNotBlank()) {
                        val newProfile = ChatProfile(
                            id = profile?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            icon = profile?.icon,
                            config = LlmChatConfig(
                                systemPrompt = systemPrompt.trim(), mcpAccess = mcpAccess
                            )
                        )
                        onSave(newProfile)
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
