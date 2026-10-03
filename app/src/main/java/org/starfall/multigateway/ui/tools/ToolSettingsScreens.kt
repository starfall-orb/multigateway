package org.starfall.multigateway.ui.tools

import org.starfall.multigateway.ui.components.AppBottomSheet
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.navigation.LocalScreenTransitionActive
import org.starfall.multigateway.ui.navigation.SlideScreenContent

@Composable
fun ToolSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min=48.dp).alpha(if (enabled) 1f else 0.38f),
        verticalAlignment=Alignment.CenterVertically
    ) {
        Text(label,Modifier.weight(1f).padding(end=8.dp))
        Switch(checked=checked,onCheckedChange=onChange,enabled=enabled)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemToolsScreen(
    providers: List<LlmProviderInfo>,
    settings: ToolSettings,
    onSave: (String, SystemToolConfig) -> Unit,
    onBack: () -> Unit
) {
    var choosing by remember { mutableStateOf<String?>(null) }
    var editingImage by remember { mutableStateOf(false) }
    val imageConfig = settings.system["generate_image"] ?: SystemToolConfig()
    val imageProvider = providers.find { it.id == imageConfig.providerId }

    SlideScreenContent(
        editor = if (editingImage && imageProvider != null) imageProvider to imageConfig else null,
        label = "Image tool settings"
    ) { page ->
    if (page != null) {
        ImageToolSettingsScreen(
            page.first,
            page.second,
            onSave = { options ->
                onSave("generate_image", page.second.withImageOptions(options))
                editingImage = false
            },
            onBack = { editingImage = false }
        )
    } else {
    BackHandler(enabled = LocalScreenTransitionActive.current, onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Default Models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("Media tools", style = MaterialTheme.typography.titleMedium)
            }
            items(listOf("generate_image", "generate_video")) { name ->
                val config = settings.system[name] ?: SystemToolConfig()
                val provider = providers.find { it.id == config.providerId }
                val model = provider?.config?.modelConfigs?.get(config.modelId)
                val available = systemMediaToolAvailable(name, config, providers)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        ToolSwitch(
                            if (name == "generate_image") "Create image" else "Create video",
                            config.enabled,
                            enabled = true
                        ) { onSave(name, config.copy(enabled = it)) }
                        Text(
                            if (model == null) "Select a model"
                            else "${provider.name} / ${model.displayName.ifBlank { config.modelId }}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { choosing = name }) { Text("Choose model") }
                        if (name == "generate_image") {
                            TextButton(
                                enabled = provider != null && model?.modelType == ModelType.IMAGE_GENERATION,
                                onClick = { editingImage = true }
                            ) { Text("Image settings") }
                        }
                    }
                }
            }

            item {
                HorizontalDivider()
                Spacer(Modifier.height(4.dp))
                Text("Conversation helpers", style = MaterialTheme.typography.titleMedium)
            }
            items(listOf("title_generation", "chat_summary")) { name ->
                val config = settings.system[name] ?: SystemToolConfig()
                val provider = providers.find { it.id == config.providerId }
                val model = provider?.config?.modelConfigs?.get(config.modelId)
                val title = if (name == "title_generation") "Title Generation" else "Chat Summary"
                val description = if (name == "title_generation")
                    "Model used to generate conversation titles."
                else
                    "Model used to summarize long conversations."
                val defaultPrompt = if (name == "title_generation")
                    DEFAULT_TITLE_GENERATION_PROMPT
                else
                    DEFAULT_CHAT_SUMMARY_PROMPT

                Card(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (model == null) "Select a text model"
                            else "${provider.name} / ${model.displayName.ifBlank { config.modelId }}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(onClick = { choosing = name }) { Text("Choose model") }
                        OutlinedTextField(
                            value = config.prompt.ifBlank { defaultPrompt },
                            onValueChange = { onSave(name, config.copy(prompt = it)) },
                            label = { Text("Instruction prompt") },
                            placeholder = { Text(defaultPrompt) },
                            supportingText = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Custom prompt instructions")
                                    Text(
                                        text = "Reset",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Bold
                                        ),
                                        modifier = Modifier.clickable {
                                            onSave(name, config.copy(prompt = defaultPrompt))
                                        }
                                    )
                                }
                            },
                            minLines = 3,
                            maxLines = 8,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }

    choosing?.let { name ->
        val type = when (name) {
            "generate_image" -> ModelType.IMAGE_GENERATION
            "generate_video" -> ModelType.VIDEO_GENERATION
            else -> ModelType.TEXT_GENERATION
        }
        val choices = providers.flatMap { provider ->
            provider.config.modelConfigs
                .filter { (id, config) ->
                    config.modelType == type && provider.config.modelIds?.contains(id) != false
                }
                .map { (id, config) -> Triple(provider, id, config) }
        }
        AppBottomSheet(
            onDismissRequest = { choosing = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(),
                contentPadding = PaddingValues(16.dp)
            ) {
                item {
                    Text("Choose ${type.displayName}", style = MaterialTheme.typography.titleLarge)
                }
                if (choices.isEmpty()) {
                    item { Text("Add a model with this type in Providers first.") }
                }
                items(choices) { (provider, id, config) ->
                    val mediaTask = name == "generate_image" || name == "generate_video"
                    val supported = !mediaTask || provider.type.isOpenAi || provider.type == ProviderType.GOOGLE
                    TextButton(
                        enabled = supported,
                        onClick = {
                            val current = settings.system[name] ?: SystemToolConfig()
                            onSave(name, current.copy(providerId = provider.id, modelId = id))
                            choosing = null
                        }
                    ) {
                        Text(
                            "${provider.name} / ${config.displayName.ifBlank { id }}" +
                                if (!supported) " (media API unsupported)" else ""
                        )
                    }
                }
            }
        }
    }
    }
    }
}

@Composable
fun ProfileMcpPermissions(
    servers: List<McpInfo>,
    access: Map<String, McpAccess>,
    toolsCache: Map<String, List<ToolDefinition>>,
    settings: ToolSettings,
    onChange: (Map<String, McpAccess>) -> Unit
) {
    Text("MCP permissions", style = MaterialTheme.typography.titleMedium)
    if (servers.isEmpty()) {
        Text("Add servers in MCP Manage first.")
        return
    }

    servers.forEach { server ->
        val policy = access[server.id] ?: McpAccess(enabled = true)
        HorizontalDivider()
        ToolSwitch(server.name, policy.enabled) { enabled ->
            onChange(access + (server.id to policy.copy(enabled = enabled)))
        }

        if (policy.enabled) {
            val cached = toolsCache[server.id] ?: server.cachedTools
            when {
                cached == null -> {
                    Text(
                        "Tool list is not cached. Save or refresh this server in MCP Manage first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> {
                    val globallyEnabled = cached.filter { tool ->
                        settings.mcpTools[server.id]?.get(tool.originalName) != false
                    }
                    if (globallyEnabled.isEmpty()) {
                        Text(
                            "No globally enabled tools available.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        globallyEnabled.forEach { tool ->
                            ToolSwitch(
                                label = tool.originalName,
                                checked = policy.tools[tool.originalName] != false
                            ) { enabled ->
                                onChange(
                                    access + (
                                        server.id to policy.copy(
                                            tools = policy.tools + (tool.originalName to enabled)
                                        )
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
