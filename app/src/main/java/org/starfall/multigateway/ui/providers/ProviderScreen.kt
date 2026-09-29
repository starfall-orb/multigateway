package org.starfall.multigateway.ui.providers

import androidx.activity.compose.BackHandler
import kotlinx.coroutines.CancellationException
import org.starfall.multigateway.data.model.ModelConfiguration
import android.widget.Toast
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import org.starfall.multigateway.data.model.AuthMethod
import org.starfall.multigateway.data.model.Authorization
import org.starfall.multigateway.data.model.LlmProviderInfo
import org.starfall.multigateway.data.model.withType
import org.starfall.multigateway.data.model.defaultAuthorization
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.ui.components.ItemOverflowMenu
import org.starfall.multigateway.ui.components.MorphingCardLayout
import org.starfall.multigateway.ui.components.longPressReorder
import org.starfall.multigateway.ui.components.moved
import org.starfall.multigateway.ui.navigation.LocalScreenTransitionActive
import org.starfall.multigateway.ui.navigation.SlideScreenContent

private data class ProviderEditor(val provider: LlmProviderInfo, val isNew: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderScreen(
    providers: List<LlmProviderInfo>,
    isGridView: Boolean = false,
    onToggleGridView: ((Boolean) -> Unit)? = null,
    onSaveProvider: (LlmProviderInfo) -> Unit,
    onSaveModels: (String, Map<String, ModelConfiguration>) -> Unit,
    onDeleteProvider: (String) -> Unit,
    onReorderProviders: (List<String>) -> Unit,
    onAuthorizeProvider: (suspend (LlmProviderInfo) -> Result<LlmProviderInfo>)? = null,
    onClearOAuthCredentials: (suspend (LlmProviderInfo) -> Result<LlmProviderInfo>)? = null,
    onTestConnection: (suspend (LlmProviderInfo, String) -> Result<String>)? = null,
    onFetchModels: (suspend (LlmProviderInfo) -> List<String>)? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var editor by remember { mutableStateOf<ProviderEditor?>(null) }
    var deletingProviderId by remember { mutableStateOf<String?>(null) }
    var orderedProviders by remember(providers) { mutableStateOf(providers) }

    SlideScreenContent(
        editor = editor,
        label = "Provider editor"
    ) { page ->
    if (page != null) {
        ProviderEditScreen(
            initialProvider = page.provider,
            isNew = page.isNew,
            onAuthorizeProvider = onAuthorizeProvider,
            onClearOAuthCredentials = onClearOAuthCredentials,
            onTestConnection = onTestConnection,
            onFetchModels = onFetchModels,
            onSaveModels = onSaveModels,
            onDismiss = {
                editor = null
            },
            onSave = { saved ->
                onSaveProvider(saved)
                editor = ProviderEditor(saved, false)
                Toast.makeText(
                    context,
                    context.getString(org.starfall.multigateway.R.string.provider_saved),
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
    } else {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Providers",
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Text(
                            text = "Manage AI providers",
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
                            contentDescription = if (isGridView) "Switch to List View" else "Switch to Grid View"
                        )
                    }
                    IconButton(onClick = {
                        editor = ProviderEditor(LlmProviderInfo(
                            id = "custom_${System.currentTimeMillis()}",
                            name = "OpenAI",
                            type = ProviderType.OPENAI,
                            baseUrl = "https://api.openai.com/v1",
                            auth = ProviderType.OPENAI.defaultAuthorization()
                        ), true)
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Provider")
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
            if (providers.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Outlined.Hub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "No Providers Added",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (isGridView) 2 else 1),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(orderedProviders, key = { _, item -> item.id }) { index, provider ->
                            ProviderUnifiedCard(
                            provider = provider,
                            isGrid = isGridView,
                            modifier = Modifier
                                .animateItem(
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                                .longPressReorder(
                                    index = index,
                                    itemCount = orderedProviders.size,
                                    columns = if (isGridView) 2 else 1,
                                    onMove = { from, to -> orderedProviders = orderedProviders.moved(from, to) },
                                    onDrop = { onReorderProviders(orderedProviders.map { it.id }) }
                                ),
                            onEdit = { editor = ProviderEditor(provider, false) },
                            onDelete = { deletingProviderId = provider.id }
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation
    if (deletingProviderId != null) {
        AlertDialog(
            onDismissRequest = { deletingProviderId = null },
            title = { Text("Delete Provider") },
            text = { Text("Are you sure you want to remove this AI provider? Stored credentials will be deleted.") },
            confirmButton = {
                Button(
                    onClick = {
                        deletingProviderId?.let { onDeleteProvider(it) }
                        deletingProviderId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deletingProviderId = null }) {
                    Text("Cancel")
                }
            }
        )
    }
    }
    }
}

@Composable
fun ProviderUnifiedCard(
    provider: LlmProviderInfo,
    isGrid: Boolean,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val shapeCorner by animateDpAsState(
        targetValue = if (isGrid) 20.dp else 16.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "providerCorner"
    )

    Surface(
        shape = RoundedCornerShape(shapeCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onEdit() }
    ) {
        MorphingCardLayout(
            isGrid = isGrid,
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            icon = {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    modifier = Modifier.size(42.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Hub,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
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
                            text = provider.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)
                        ) {
                            Text(
                                text = provider.type.displayName,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                    Text(
                        text = provider.baseUrl,
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

fun AuthMethod.displayName(): String = when (this) {
    AuthMethod.BEARER_TOKEN -> "Bearer Token"
    AuthMethod.QUERY_PARAM -> "Query Parameter"
    AuthMethod.CUSTOM_HEADER -> "Custom Header"
    AuthMethod.OAUTH -> "OAuth"
    AuthMethod.OTHER -> "Other"
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProviderEditScreen(
    initialProvider: LlmProviderInfo,
    isNew: Boolean,
    onAuthorizeProvider: (suspend (LlmProviderInfo) -> Result<LlmProviderInfo>)? = null,
    onClearOAuthCredentials: (suspend (LlmProviderInfo) -> Result<LlmProviderInfo>)? = null,
    onTestConnection: (suspend (LlmProviderInfo, String) -> Result<String>)? = null,
    onFetchModels: (suspend (LlmProviderInfo) -> List<String>)? = null,
    onSaveModels: (String, Map<String, ModelConfiguration>) -> Unit,
    onDismiss: () -> Unit,
    onSave: (LlmProviderInfo) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initialProvider.name) }
    var type by remember { mutableStateOf(initialProvider.type) }
    var baseUrl by remember { mutableStateOf(initialProvider.baseUrl) }
    var authMethod by remember { mutableStateOf(initialProvider.auth.method) }
    var authName by remember { mutableStateOf(
        if (initialProvider.auth.method in listOf(AuthMethod.CUSTOM_HEADER, AuthMethod.QUERY_PARAM)) {
            initialProvider.auth.key.orEmpty().ifBlank { if (initialProvider.type == ProviderType.GOOGLE) "key" else "" }
        } else ""
    ) }
    var apiKey by remember { mutableStateOf(if (initialProvider.auth.method in listOf(AuthMethod.CUSTOM_HEADER, AuthMethod.QUERY_PARAM)) initialProvider.auth.value.orEmpty() else initialProvider.auth.token) }
    var oauthAuthorized by remember(initialProvider.id) {
        mutableStateOf(
            initialProvider.auth.method == AuthMethod.OAUTH &&
                initialProvider.auth.value?.isNotBlank() == true
        )
    }
    var oauthIdentity by remember(initialProvider.id) {
        mutableStateOf(initialProvider.auth.key?.takeIf { oauthAuthorized })
    }
    var oauthMarker by remember(initialProvider.id) {
        mutableStateOf(initialProvider.auth.value.orEmpty().takeIf { oauthAuthorized }.orEmpty())
    }
    var oauthAuthorizing by remember { mutableStateOf(false) }
    var oauthAuthError by remember { mutableStateOf<String?>(null) }
    fun authorization() = if (authMethod == AuthMethod.OAUTH) {
        Authorization(
            AuthMethod.OAUTH,
            key = oauthIdentity,
            value = if (oauthAuthorized) oauthMarker else ""
        )
    } else {
        Authorization(
            authMethod,
            if (authMethod in listOf(AuthMethod.CUSTOM_HEADER, AuthMethod.QUERY_PARAM)) authName.trim() else null,
            apiKey.trim()
        )
    }
    var showTestDialog by remember { mutableStateOf(false) }
    val testingModels = remember { mutableStateMapOf<String, Boolean>() }
    val modelTestResults = remember { mutableStateMapOf<String, Result<String>>() }
    var typeExpanded by remember { mutableStateOf(false) }
    var authExpanded by remember { mutableStateOf(false) }
    var supportStream by remember { mutableStateOf(initialProvider.config.supportStream) }
    val headerRows = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            addAll(initialProvider.config.headers.entries.map { it.key to it.value })
        }
    }
    var selectedTab by remember { mutableStateOf(0) }
    var modelConfigs by remember {
        mutableStateOf(initialProvider.config.modelIds?.associateWith {
            initialProvider.config.modelConfigs[it] ?: ModelConfiguration()
        } ?: initialProvider.config.modelConfigs)
    }
    var configuringModel by remember { mutableStateOf<String?>(null) }
    var showModelCatalog by remember { mutableStateOf(false) }
    val activeHeaders = headerRows.filterNot { (key, value) -> key.isBlank() && value.isBlank() }
    val headersValid = activeHeaders.all { (key, value) ->
        key.isNotBlank() &&
            key.none { it <= ' ' || it == ':' || it.code >= 127 } &&
            value.none { it == '\r' || it == '\n' }
    } && activeHeaders.map { it.first.lowercase() }.distinct().size == activeHeaders.size
    val parsedHeaders = if (headersValid) activeHeaders.associate { it.first.trim() to it.second } else null
    val urlValid = runCatching { org.starfall.multigateway.data.tools.providerBase(initialProvider.copy(baseUrl = baseUrl)) }.isSuccess
    val authValid = authMethod !in listOf(AuthMethod.CUSTOM_HEADER, AuthMethod.QUERY_PARAM) ||
        (authName.isNotBlank() && authName.none { it <= ' ' || it == ':' || it.code >= 127 })
    val requestValid = urlValid && authValid && headersValid
    fun requestConfig() = initialProvider.config.copy(
        supportStream = supportStream, headers = parsedHeaders ?: initialProvider.config.headers,
        modelConfigs = modelConfigs, modelIds = modelConfigs.keys.toList()
    )
    fun dismissEditor() {
        if (isNew && oauthAuthorized && onClearOAuthCredentials != null) {
            val oauthProvider = initialProvider.copy(type = type, auth = authorization())
            coroutineScope.launch {
                try {
                    onClearOAuthCredentials.invoke(oauthProvider)
                } finally {
                    onDismiss()
                }
            }
        } else {
            onDismiss()
        }
    }
    BackHandler(enabled = LocalScreenTransitionActive.current && configuringModel == null, onBack = ::dismissEditor)
    fun updateModels(updated: Map<String, ModelConfiguration>) {
        modelConfigs = updated
        if (!isNew) onSaveModels(initialProvider.id, updated)
    }

    SlideScreenContent(editor = configuringModel, label = "Model editor") { editingModelId ->
    if (editingModelId != null) {
        key(editingModelId) {
            ModelEditScreen(
                provider = initialProvider.copy(type = type, config = requestConfig()),
                initialModelId = editingModelId,
                existingModelIds = modelConfigs.keys,
                onSave = { newId, config ->
                    updateModels(modelConfigs.entries.associate { (id, value) ->
                        if (id == editingModelId) newId to config else id to value
                    })
                    configuringModel = null
                },
                onBack = { configuringModel = null }
            )
        }
    } else {
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        topBar = {
            TopAppBar(
                title = { Text(if (isNew) "Add Provider" else "Provider Edit") },
                navigationIcon = {
                    IconButton(onClick = ::dismissEditor) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedTab == 0) {
                        Button(
                            enabled = requestValid && baseUrl.isNotBlank() &&
                                (authMethod != AuthMethod.OAUTH || (oauthAuthorized && !oauthAuthorizing)),
                            onClick = {
                                onSave(initialProvider.copy(
                                    name = name.trim().ifEmpty { type.defaultName },
                                    type = type,
                                    baseUrl = baseUrl.trim(),
                                    auth = authorization(),
                                    config = requestConfig()
                                ))
                            },
                            modifier = Modifier.padding(end = 8.dp)
                        ) { Text("Save") }
                    } else {
                        IconButton(onClick = { configuringModel = "" }) {
                            Icon(
                                imageVector = Icons.Outlined.Edit,
                                contentDescription = "Add model manually"
                            )
                        }
                        IconButton(onClick = { showModelCatalog = true }) {
                            Icon(
                                imageVector = Icons.Outlined.FormatListBulleted,
                                contentDescription = "Open model catalog"
                            )
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Outlined.Build, contentDescription = null) },
                    label = { Text("Configuration") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Outlined.Inventory2, contentDescription = null) },
                    label = { Text("Models") }
                )
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (selectedTab == 0) {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Provider Type Dropdown
                    ExposedDropdownMenuBox(
                        expanded = typeExpanded,
                        onExpandedChange = { typeExpanded = !typeExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = type.displayName,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Provider Type") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )

                        ExposedDropdownMenu(
                            expanded = typeExpanded,
                            onDismissRequest = { typeExpanded = false }
                        ) {
                            ProviderType.entries.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(t.displayName) },
                                    onClick = {
                                        if (t != type) {
                                            val updated = initialProvider.copy(
                                                name = name,
                                                type = type,
                                                baseUrl = baseUrl
                                            ).withType(t)
                                            name = updated.name
                                            baseUrl = updated.baseUrl
                                            type = updated.type
                                            val defaultAuth = t.defaultAuthorization()
                                            authMethod = defaultAuth.method
                                            authName = defaultAuth.key.orEmpty()
                                            apiKey = defaultAuth.value.orEmpty()
                                            oauthAuthorized = false
                                            oauthIdentity = null
                                            oauthMarker = ""
                                            oauthAuthError = null
                                        }
                                        typeExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Provider Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text("Base API Endpoint / URL") },
                        isError = !urlValid,
                        supportingText = { if (!urlValid) Text("Use an HTTP(S) base URL without query, fragment or embedded credentials.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Default URL suggestions for each provider type
                    when (type) {
                        ProviderType.OLLAMA -> {
                            SuggestionChip(
                                onClick = { baseUrl = "https://ollama.com/api" },
                                label = { Text("Default: https://ollama.com/api", fontSize = 11.sp) },
                                icon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                        ProviderType.OPENAI_CODEX -> {
                            SuggestionChip(
                                onClick = { baseUrl = ProviderType.OPENAI_CODEX.defaultBaseUrl },
                                label = { Text("Default: ChatGPT Codex backend", fontSize = 11.sp) },
                                icon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                        ProviderType.OPENAI, ProviderType.OPENAI_RESPONSES -> {
                            SuggestionChip(
                                onClick = { baseUrl = "https://api.openai.com/v1" },
                                label = { Text("Default: https://api.openai.com/v1", fontSize = 11.sp) },
                                icon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                        ProviderType.GOOGLE -> {
                            SuggestionChip(
                                onClick = { baseUrl = "https://generativelanguage.googleapis.com/v1beta" },
                                label = { Text("Default: https://generativelanguage.googleapis.com/v1beta", fontSize = 11.sp) },
                                icon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                        ProviderType.ANTHROPIC -> {
                            SuggestionChip(
                                onClick = { baseUrl = "https://api.anthropic.com/v1" },
                                label = { Text("Default: https://api.anthropic.com/v1", fontSize = 11.sp) },
                                icon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp)) }
                            )
                        }
                    }

                    if (baseUrl.trim().startsWith("http://", true)) {
                        Text("HTTP is unencrypted. API keys, headers and messages can be read on the network. Use HTTPS outside a trusted local network.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    Text("Authorization", style = MaterialTheme.typography.titleMedium)
                    Box {
                        OutlinedButton(onClick = { authExpanded = true }) {
                            Text(authMethod.displayName())
                            Spacer(Modifier.width(4.dp))
                            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                        }
                        DropdownMenu(expanded = authExpanded, onDismissRequest = { authExpanded = false }) {
                            AuthMethod.entries
                                .filter { type != ProviderType.OPENAI_CODEX || it == AuthMethod.OAUTH }
                                .forEach { method ->
                                    DropdownMenuItem(
                                        text = { Text(method.displayName()) },
                                        onClick = {
                                            authMethod = method
                                            oauthAuthError = null
                                            authExpanded = false
                                        }
                                    )
                                }
                        }
                    }

                    if (authMethod == AuthMethod.OAUTH) {
                        Text(
                            text = when {
                                oauthAuthorizing -> "Waiting for OAuth authorization in your browser…"
                                oauthAuthorized && !oauthIdentity.isNullOrBlank() -> "OAuth authorized as $oauthIdentity"
                                oauthAuthorized -> "OAuth credentials are saved"
                                type == ProviderType.OPENAI_CODEX -> "Sign in with the ChatGPT account that has Codex access."
                                else -> "Authorize this provider in your device browser."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (oauthAuthorized) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = !oauthAuthorizing && onAuthorizeProvider != null,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    coroutineScope.launch {
                                        oauthAuthorizing = true
                                        oauthAuthError = null
                                        val draft = initialProvider.copy(
                                            name = name.trim().ifEmpty { type.defaultName },
                                            type = type,
                                            baseUrl = baseUrl.trim().ifEmpty { type.defaultBaseUrl },
                                            auth = Authorization(
                                                AuthMethod.OAUTH,
                                                key = oauthIdentity,
                                                value = oauthMarker
                                            ),
                                            config = requestConfig()
                                        )
                                        val result = onAuthorizeProvider?.invoke(draft)
                                            ?: Result.failure(IllegalStateException("OAuth is unavailable for this provider."))
                                        result.onSuccess { authorized ->
                                            oauthAuthorized = true
                                            oauthIdentity = authorized.auth.key
                                            oauthMarker = authorized.auth.value.orEmpty()
                                            baseUrl = authorized.baseUrl
                                        }.onFailure { error ->
                                            oauthAuthError = error.message ?: "OAuth authorization failed."
                                        }
                                        oauthAuthorizing = false
                                    }
                                }
                            ) {
                                if (oauthAuthorizing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Icon(Icons.Outlined.OpenInBrowser, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(if (oauthAuthorized) "OAuth again" else "Open OAuth in browser")
                            }

                            if (oauthAuthorized) {
                                OutlinedButton(
                                    enabled = !oauthAuthorizing && onClearOAuthCredentials != null,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        coroutineScope.launch {
                                            oauthAuthorizing = true
                                            oauthAuthError = null
                                            val current = initialProvider.copy(
                                                name = name.trim().ifEmpty { type.defaultName },
                                                type = type,
                                                baseUrl = baseUrl.trim().ifEmpty { type.defaultBaseUrl },
                                                auth = authorization(),
                                                config = requestConfig()
                                            )
                                            val result = onClearOAuthCredentials?.invoke(current)
                                                ?: Result.failure(IllegalStateException("OAuth credential removal is unavailable."))
                                            result.onSuccess {
                                                oauthAuthorized = false
                                                oauthIdentity = null
                                                oauthMarker = ""
                                            }.onFailure { error ->
                                                oauthAuthError = error.message ?: "Could not remove OAuth credentials."
                                            }
                                            oauthAuthorizing = false
                                        }
                                    }
                                ) {
                                    Icon(Icons.Outlined.Delete, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Delete OAuth credentials")
                                }
                            }
                        }
                        oauthAuthError?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        if (authMethod in listOf(AuthMethod.CUSTOM_HEADER, AuthMethod.QUERY_PARAM)) {
                            OutlinedTextField(authName, { authName = it }, label = { Text(if (authMethod == AuthMethod.CUSTOM_HEADER) "Header name" else "Query parameter name") },
                                isError = !authValid, singleLine = true, modifier = Modifier.fillMaxWidth())
                        }
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(if (type == ProviderType.OLLAMA) "API Key / Token (Optional)" else "API Key") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    HorizontalDivider()
                    Text("Request config", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Stream responses", modifier = Modifier.weight(1f).padding(end = 12.dp))
                        Switch(checked = supportStream, onCheckedChange = { supportStream = it })
                    }
                    HorizontalDivider()
                    Text("Custom Headers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        "Add custom HTTP headers for provider requests",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    headerRows.forEachIndexed { index, row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = row.first,
                                onValueChange = { headerRows[index] = it to headerRows[index].second },
                                placeholder = { Text("Header") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = row.second,
                                onValueChange = { headerRows[index] = headerRows[index].first to it },
                                placeholder = { Text("Value") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                headerRows.removeAt(index)
                            }) {
                                Icon(Icons.Outlined.Delete, contentDescription = "Delete header")
                            }
                        }
                    }
                    if (!headersValid) {
                        Text(
                            "Header names and values must be valid and header names must be unique.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        onClick = { headerRows.add("" to "") },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(28.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add Header")
                    }

                    OutlinedButton(
                        onClick = { showTestDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = baseUrl.isNotBlank() && requestValid && modelConfigs.isNotEmpty()
                    ) {
                        Icon(Icons.Outlined.NetworkCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Connection")
                    }
                }
            } else {
                val models = modelConfigs.keys.toList()
                fun moveModel(from: Int, to: Int) {
                    val reordered = models.moved(from, to)
                    modelConfigs = reordered.associateWith { id ->
                        modelConfigs[id] ?: ModelConfiguration()
                    }
                }
                fun persistModelOrder() {
                    if (!isNew) onSaveModels(initialProvider.id, modelConfigs)
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (models.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    "No models have been added to this provider yet.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    OutlinedButton(onClick = { configuringModel = "" }) {
                                        Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Add manually")
                                    }
                                    Button(onClick = { showModelCatalog = true }) {
                                        Icon(Icons.Outlined.FormatListBulleted, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Open model catalog")
                                    }
                                }
                            }
                        }
                    }
                    itemsIndexed(models, key = { _, id -> id }) { index, modelId ->
                        ListItem(
                            headlineContent = {
                                Text(
                                    modelConfigs[modelId]?.displayName?.ifBlank { modelId } ?: modelId,
                                    overflow = TextOverflow.Ellipsis,
                                    maxLines = 2
                                )
                            },
                            supportingContent = {
                                Text("$modelId · ${modelConfigs[modelId]?.modelType?.displayName.orEmpty()}")
                            },
                            trailingContent = {
                                ItemOverflowMenu(
                                    onEdit = { configuringModel = modelId },
                                    onDelete = { updateModels(modelConfigs - modelId) },
                                    deleteColor = MaterialTheme.colorScheme.error
                                )
                            },
                            modifier = Modifier
                                .animateItem(
                                    placementSpec = spring(
                                        dampingRatio = Spring.DampingRatioNoBouncy,
                                        stiffness = Spring.StiffnessMediumLow
                                    )
                                )
                                .longPressReorder(
                                    index = index,
                                    itemCount = models.size,
                                    columns = 1,
                                    onMove = ::moveModel,
                                    onDrop = ::persistModelOrder
                                )
                        )
                    }
                }
            }
        }
    }
    if (showTestDialog) {
        val testTarget = initialProvider.copy(
            name = name.trim().ifEmpty { "Provider" },
            type = type,
            baseUrl = baseUrl.trim(),
            auth = authorization(),
            config = requestConfig()
        )
        AlertDialog(
            onDismissRequest = { showTestDialog = false },
            title = { Text("Test Connection") },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        Text(
                            "Use the Test button for a model to start testing. Results appear directly below that model.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    items(modelConfigs.keys.toList(), key = { it }) { modelId ->
                        val testing = testingModels[modelId] == true
                        val result = modelTestResults[modelId]
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        val config = modelConfigs[modelId] ?: ModelConfiguration()
                                        Text(config.displayName.ifBlank { modelId }, fontWeight = FontWeight.SemiBold)
                                        if (config.displayName.isNotBlank()) {
                                            Text(modelId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    IconButton(
                                        enabled = !testing,
                                        onClick = {
                                            testingModels[modelId] = true
                                            modelTestResults.remove(modelId)
                                            coroutineScope.launch {
                                                val tested = try {
                                                    onTestConnection?.invoke(testTarget, modelId)
                                                        ?: Result.success("Model responded successfully")
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Exception) {
                                                    Result.failure(e)
                                                }
                                                modelTestResults[modelId] = tested
                                                testingModels[modelId] = false
                                            }
                                        }
                                    ) {
                                        if (testing) {
                                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                        } else {
                                            Icon(Icons.Outlined.NetworkCheck, contentDescription = "Test $modelId")
                                        }
                                    }
                                    IconButton(
                                        enabled = !testing,
                                        onClick = {
                                            testingModels.remove(modelId)
                                            modelTestResults.remove(modelId)
                                            updateModels(modelConfigs - modelId)
                                        }
                                    ) {
                                        Icon(
                                            Icons.Outlined.Delete,
                                            contentDescription = "Delete $modelId",
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                                result?.let { tested ->
                                    Spacer(Modifier.height(8.dp))
                                    Row(verticalAlignment = Alignment.Top) {
                                        Icon(
                                            if (tested.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                                            contentDescription = null,
                                            tint = if (tested.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            tested.getOrNull() ?: tested.exceptionOrNull()?.localizedMessage ?: "Connection failed",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (tested.isSuccess) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTestDialog = false }) { Text("Close") } }
        )
    }

    if (showModelCatalog) {
        ProviderModelCatalogSheet(
            provider = initialProvider.copy(
                name = name, type = type, baseUrl = baseUrl.trim(),
                auth = authorization(),
                config = requestConfig()
            ),
            selectedModels = modelConfigs.keys,
            onFetchModels = onFetchModels,
            onToggle = { id ->
                updateModels(if (id in modelConfigs) modelConfigs - id else modelConfigs + (id to ModelConfiguration()))
            },
            onSetSelection = { ids, selected ->
                updateModels(if (selected) {
                    modelConfigs + ids.associateWith { modelConfigs[it] ?: ModelConfiguration() }
                } else {
                    modelConfigs - ids.toSet()
                })
            },
            onDismiss = { showModelCatalog = false }
        )
    }

    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderModelCatalogSheet(
    provider: LlmProviderInfo,
    selectedModels: Set<String>,
    onFetchModels: (suspend (LlmProviderInfo) -> List<String>)?,
    onToggle: (String) -> Unit,
    onSetSelection: (List<String>, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(refresh) {
        loading = true
        loadError = null
        try {
            models = (onFetchModels ?: error("Model discovery is unavailable"))(provider).distinct()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = e.localizedMessage ?: "Unable to load models"
        } finally {
            loading = false
        }
    }
    // The list and bulk action use exactly the same filtered IDs.
    val visibleModels = (models + selectedModels).distinct().filter { it.contains(query, ignoreCase = true) }
    val allVisibleSelected = visibleModels.isNotEmpty() && visibleModels.all { it in selectedModels }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight().imePadding().padding(horizontal = 16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(onClick = { refresh++ }, enabled = !loading) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh models")
                }
                OutlinedTextField(query, { query = it }, label = { Text("Search models") },
                    singleLine = true, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { onSetSelection(visibleModels, !allVisibleSelected) },
                    enabled = visibleModels.isNotEmpty()
                ) {
                    Icon(
                        imageVector = if (allVisibleSelected) Icons.Outlined.Close else Icons.Outlined.SelectAll,
                        contentDescription = if (allVisibleSelected) "Remove all visible models" else "Select all visible models",
                        tint = when {
                            visibleModels.isEmpty() -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            allVisibleSelected -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.primary
                        }
                    )
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            loadError?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { refresh++ }, enabled = !loading) { Text("Retry") }
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (visibleModels.isEmpty() && !loading && loadError == null) {
                    item { Text(if (query.isBlank()) "No models returned." else "No matching models.", modifier = Modifier.padding(16.dp)) }
                }
                items(visibleModels, key = { it }) { id ->
                    val added = id in selectedModels
                    ListItem(
                        headlineContent = { Text(id, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(if (added) "Added" else "Not added") },
                        trailingContent = {
                            IconButton(onClick = { onToggle(id) }) {
                                Icon(if (added) Icons.Outlined.Close else Icons.Default.Add,
                                    contentDescription = if (added) "Remove $id" else "Add $id",
                                    tint = if (added) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                            }
                        }
                    )
                }
            }
        }
    }
}
