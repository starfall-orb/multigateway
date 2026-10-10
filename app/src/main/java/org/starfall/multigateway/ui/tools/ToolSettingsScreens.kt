package org.starfall.multigateway.ui.tools
import org.starfall.multigateway.ui.components.SelectableOutlinedTextField

import org.starfall.multigateway.ui.components.AppBottomSheet
import org.starfall.multigateway.ui.settings.SettingsCard
import org.starfall.multigateway.ui.chat.ModelPickerSheet
import org.starfall.multigateway.ui.components.EntityIcon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.ui.text.style.TextOverflow
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.saveable.rememberSaveable
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
import org.starfall.multigateway.data.tools.videoOptionFields
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
    providerGroups: List<ProviderGroup> = emptyList(),
    collapsedGroupIds: Set<String> = emptySet(),
    collapsedProviderIds: Set<String> = emptySet(),
    onCollapsedGroupIdsChange: (Set<String>) -> Unit = {},
    onCollapsedProviderIdsChange: (Set<String>) -> Unit = {},
    onBack: () -> Unit
) {
    var choosing by remember { mutableStateOf<String?>(null) }
    var editingPrompt by rememberSaveable { mutableStateOf<String?>(null) }
    var editingMedia by remember { mutableStateOf<String?>(null) }
    val mediaConfig = settings.system[editingMedia] ?: SystemToolConfig()
    val mediaProvider = providers.find { it.id == mediaConfig.providerId }

    SlideScreenContent(
        editor = editingMedia?.let { name -> mediaProvider?.let { Triple(name, it, mediaConfig) } },
        label = "Media tool settings"
    ) { page ->
    if (page != null) {
        ImageToolSettingsScreen(
            page.second,
            page.third,
            onSave = { options ->
                onSave(page.first, if (page.first == "generate_video") page.third.withVideoOptions(options) else page.third.withImageOptions(options))
                editingMedia = null
            },
            onBack = { editingMedia = null },
            video = page.first == "generate_video"
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
                Text("Media tools", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            }
            items(listOf("generate_image", "generate_video")) { name ->
                val config = settings.system[name] ?: SystemToolConfig()
                val provider = providers.find { it.id == config.providerId }
                val model = provider?.config?.modelConfigs?.get(config.modelId)
                val available = systemMediaToolAvailable(name, config, providers)
                MediaDefaultModelCard(
                    name = name,
                    config = config,
                    provider = provider,
                    model = model,
                    available = available,
                    onEnabledChange = { onSave(name, config.copy(enabled = it)) },
                    onChooseModel = { choosing = name },
                    onOpenSettings = { editingMedia = name }
                )
            }

            item {
                Text("Conversation helpers", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
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

                SettingsCard {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    DefaultModelSelector(provider, config.modelId, model, onClick = { choosing = name })
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { editingPrompt = name }) { Text("Instructions") }
                    }
                }
            }
        }
    }

    editingPrompt?.let { name ->
        val config = settings.system[name] ?: SystemToolConfig()
        val defaultPrompt = if (name == "title_generation") DEFAULT_TITLE_GENERATION_PROMPT else DEFAULT_CHAT_SUMMARY_PROMPT
        var prompt by rememberSaveable(name) { mutableStateOf(config.prompt.ifBlank { defaultPrompt }) }
        AppBottomSheet(
            onDismissRequest = { editingPrompt = null }
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(if (name == "title_generation") "Title Generation" else "Chat Summary",
                    style = MaterialTheme.typography.titleLarge)
                Text("Customize the instructions sent to this default model.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SelectableOutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("Instruction prompt") },
                    minLines = 5,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { prompt = defaultPrompt }) { Text("Reset") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { editingPrompt = null }) { Text("Cancel") }
                    Button(onClick = {
                        onSave(name, config.copy(prompt = prompt.ifBlank { defaultPrompt }))
                        editingPrompt = null
                    }) { Text("Save") }
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
        val current = settings.system[name] ?: SystemToolConfig()
        ModelPickerSheet(
            providers = providers,
            providerGroups = providerGroups,
            collapsedGroupIdsState = collapsedGroupIds,
            collapsedProviderIdsState = collapsedProviderIds,
            onCollapsedGroupIdsChange = onCollapsedGroupIdsChange,
            onCollapsedProviderIdsChange = onCollapsedProviderIdsChange,
            selectedProviderId = current.providerId,
            selectedModelId = current.modelId,
            conversationReasoningEffort = null,
            onSetReasoningEffort = {},
            showReasoningEffort = false,
            modelFilter = { provider, id, config ->
                config.modelType == type &&
                    provider.config.modelConfigs.containsKey(id) &&
                    provider.config.modelIds?.contains(id) != false &&
                    (type == ModelType.TEXT_GENERATION || provider.type.isOpenAi || provider.type == ProviderType.GOOGLE)
            },
            onSelectModel = { providerId, modelId ->
                onSave(name, current.copy(providerId = providerId, modelId = modelId))
            },
            onDismiss = { choosing = null }
        )
    }
    }
    }
}

@Composable
private fun DefaultModelSelector(
    provider: LlmProviderInfo?,
    modelId: String,
    model: ModelConfiguration?,
    onClick: () -> Unit
) {
    val modelName = model?.displayName?.ifBlank { modelId }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EntityIcon(
                image = model?.icon,
                modifier = Modifier.size(48.dp),
                text = modelId.substringAfterLast('/').take(1).uppercase(),
                fallback = if (model == null) Icons.Outlined.Add else null,
                matchName = modelName,
                model = true
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    modelName ?: "Select a model",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (model == null) "Tap to choose a model" else provider?.name.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MediaDefaultModelCard(
    name: String,
    config: SystemToolConfig,
    provider: LlmProviderInfo?,
    model: ModelConfiguration?,
    available: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onChooseModel: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val image = name == "generate_image"
    val title = if (image) "Image Generation" else "Video Generation"
    val description = if (image) {
        "Generate images from prompts using the selected model."
    } else {
        "Generate videos from prompts using the selected model."
    }
    val modelType = if (image) ModelType.IMAGE_GENERATION else ModelType.VIDEO_GENERATION
    val settingsAvailable = provider != null && model?.modelType == modelType &&
        (image || videoOptionFields(provider, config.modelId).isNotEmpty() || config.videoOptions.isNotEmpty())

    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = if (image) Icons.Outlined.Image else Icons.Outlined.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(checked = config.enabled, onCheckedChange = onEnabledChange)
        }
        Spacer(Modifier.height(14.dp))
        DefaultModelSelector(provider, config.modelId, model, onClick = onChooseModel)
        if (!available) {
            Text(
                "Select a compatible model to enable this tool.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(enabled = settingsAvailable, onClick = onOpenSettings) {
                Text(if (image) "Image settings" else "Video settings")
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
