package org.starfall.multigateway.ui.providers
import org.starfall.multigateway.ui.components.SelectableOutlinedTextField

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.starfall.multigateway.R
import org.starfall.multigateway.data.local.preferences.ModelConfigurationMemory
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.tools.providerBase
import org.starfall.multigateway.ui.components.IconPickerRow
import org.starfall.multigateway.ui.components.providerInitials
import org.starfall.multigateway.ui.components.RoundedDropdownMenuItem
import org.starfall.multigateway.ui.components.moved
import org.starfall.multigateway.ui.components.rememberOAuthStart
import org.starfall.multigateway.ui.navigation.LocalScreenTransitionActive
import org.starfall.multigateway.ui.navigation.SlideScreenContent

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ProviderEditScreen(
    initialProvider: LlmProviderInfo, isNew: Boolean,
    onAuthorizeProvider: (suspend (LlmProviderInfo) -> Result<LlmProviderInfo>)? = null,
    onClearOAuthCredentials: (suspend (LlmProviderInfo) -> Result<LlmProviderInfo>)? = null,
    onTestConnection: (suspend (LlmProviderInfo, String) -> Result<String>)? = null,
    onFetchModels: (suspend (LlmProviderInfo) -> List<DiscoveredModel>)? = null,
    onSaveModels: (String, Map<String, ModelConfiguration>) -> Unit, onReorderModels: (String, List<String>) -> Unit,
    onDismiss: () -> Unit, onSave: (LlmProviderInfo) -> Unit
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialProvider.name) }
    var providerIcon by remember { mutableStateOf(initialProvider.icon) }
    var iconImporting by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(initialProvider.type) }
    var baseUrl by remember { mutableStateOf(initialProvider.baseUrl) }
    var authMethod by remember { mutableStateOf(initialProvider.auth.editMethod()) }
    var authName by remember { mutableStateOf(initialProvider.auth.key.orEmpty().takeIf {
        initialProvider.auth.method in listOf(AuthMethod.BEARER_TOKEN, AuthMethod.CUSTOM_HEADER, AuthMethod.QUERY_PARAM)
    }.orEmpty().ifBlank { if (initialProvider.auth.method == AuthMethod.QUERY_PARAM) "key" else "Authorization" }) }
    var apiKey by remember { mutableStateOf(initialProvider.auth.token) }
    var oauthAuthorized by remember(initialProvider.id) { mutableStateOf(initialProvider.auth.method == AuthMethod.OAUTH && initialProvider.auth.value?.isNotBlank() == true) }
    var oauthIdentity by remember(initialProvider.id) { mutableStateOf(initialProvider.auth.key?.takeIf { oauthAuthorized }) }
    var oauthMarker by remember(initialProvider.id) { mutableStateOf(initialProvider.auth.value.orEmpty().takeIf { oauthAuthorized }.orEmpty()) }
    var providerPersisted by remember(initialProvider.id, isNew) { mutableStateOf(!isNew) }
    var oauthAuthorizing by remember { mutableStateOf(false) }
    var oauthClearing by remember { mutableStateOf(false) }
    var oauthAuthError by remember { mutableStateOf<String?>(null) }
    fun authorization() = if (authMethod == AuthMethod.OAUTH) Authorization(AuthMethod.OAUTH, key = oauthIdentity, value = if (oauthAuthorized) oauthMarker else "")
        else Authorization(authMethod, if (authMethod in listOf(AuthMethod.BEARER_TOKEN, AuthMethod.QUERY_PARAM)) authName.trim() else null,
            if (authMethod == AuthMethod.BEARER_TOKEN) bearerHeaderValue(authName.ifBlank { "Authorization" }, apiKey) else apiKey.trim())
    var showTestDialog by remember { mutableStateOf(false) }
    val currentTest by rememberUpdatedState(onTestConnection)
    val tests = remember(scope, initialProvider.id) { ModelConnectionTests(scope) { provider, id ->
        currentTest?.invoke(provider, id) ?: Result.failure(IllegalStateException("Connection testing is unavailable."))
    } }
    val context = LocalContext.current
    val memory = remember(context) { ModelConfigurationMemory(context) }
    LaunchedEffect(initialProvider.id) { initialProvider.config.modelConfigs.forEach { (id, config) -> memory.remember(id, config, onlyIfMissing = true) } }
    var typeExpanded by remember { mutableStateOf(false) }
    var supportStream by remember { mutableStateOf(initialProvider.config.supportStream) }
    val headerRows = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(initialProvider.config.headers.entries.map { it.key to it.value }) } }
    val pager = rememberPagerState(pageCount = { 2 })
    // This state outlives the nested model editor, retaining its scroll position.
    val modelList = rememberSaveable(initialProvider.id, saver = LazyListState.Saver) { LazyListState() }
    var models by remember { mutableStateOf(initialProvider.config.modelIds?.associateWith { initialProvider.config.modelConfigs[it] ?: ModelConfiguration() }
        ?: initialProvider.config.modelConfigs) }
    var order by remember { mutableStateOf(models.keys.toList()) }
    var editingModel by remember { mutableStateOf<String?>(null) }
    var showCatalog by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf<Map<String, DiscoveredModel>>(emptyMap()) }
    val activeHeaders = headerRows.filterNot { (key, value) -> key.isBlank() && value.isBlank() }
    val headersValid = activeHeaders.all { (key, value) -> key.isNotBlank() && key.none { it <= ' ' || it == ':' || it.code >= 127 } && value.none { it == '\r' || it == '\n' } } &&
        activeHeaders.map { it.first.lowercase() }.distinct().size == activeHeaders.size
    val parsedHeaders = if (headersValid) activeHeaders.associate { it.first.trim() to it.second } else null
    val urlValid = baseUrl.isBlank() || runCatching { providerBase(initialProvider.copy(baseUrl = baseUrl)) }.isSuccess
    val authValid = apiKey.isBlank() || authMethod !in listOf(AuthMethod.BEARER_TOKEN, AuthMethod.QUERY_PARAM) || authName.isBlank() || authName.none { it <= ' ' || it == ':' || it.code >= 127 }
    val valid = urlValid && authValid && headersValid
    fun requestConfig() = initialProvider.config.copy(supportStream = supportStream, headers = parsedHeaders ?: initialProvider.config.headers,
        modelConfigs = order.associateWith { models.getValue(it) }, modelIds = order)
    fun draft() = initialProvider.copy(name = name.trim().ifEmpty { type.defaultName }, icon = providerIcon, type = type,
        baseUrl = baseUrl.trim(), auth = authorization(), config = requestConfig())
    fun updateModels(updated: Map<String, ModelConfiguration>) {
        val existing = order.toSet()
        order = order.filter { it in updated } + updated.keys.filterNot { it in existing }
        models = updated
        if (providerPersisted) onSaveModels(initialProvider.id, order.associateWith { updated.getValue(it) })
    }
    BackHandler(enabled = LocalScreenTransitionActive.current && editingModel == null, onBack = onDismiss)
    SlideScreenContent(editor = editingModel, label = "Model editor") { modelId ->
        if (modelId != null) key(modelId) {
            ModelEditScreen(initialProvider.copy(type = type, config = requestConfig()), modelId, models.keys,
                onSave = { id, config -> memory.remember(id, config); updateModels((models - modelId) + (id to config)); editingModel = null },
                onBack = { editingModel = null })
        } else {
            Scaffold(modifier = Modifier.fillMaxSize().imePadding(), topBar = {
                TopAppBar(title = { Text(if (isNew) stringResource(R.string.new_provider) else name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back)) } },
                    actions = {
                        if (pager.currentPage == 0) Button(enabled = valid && !oauthAuthorizing && !iconImporting,
                            onClick = { onSave(draft()) }, modifier = Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.common_save)) }
                        else {
                            IconButton(onClick = { showTestDialog = true }, enabled = onTestConnection != null && baseUrl.isNotBlank() && valid && models.isNotEmpty()) {
                                Icon(Icons.Outlined.NetworkCheck, stringResource(R.string.test_connection_lower))
                            }
                            IconButton(onClick = { editingModel = "" }) { Icon(Icons.Default.Add, stringResource(R.string.add_model_manually)) }
                            IconButton(onClick = { showCatalog = true }) { Icon(Icons.Outlined.FormatListBulleted, stringResource(R.string.open_model_catalog)) }
                        }
                    })
            }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    TabRow(selectedTabIndex = pager.currentPage) {
                        Tab(pager.currentPage == 0, { scope.launch { pager.animateScrollToPage(0) } }, text = { Text(stringResource(R.string.provider_configuration)) })
                        Tab(pager.currentPage == 1, { scope.launch { pager.animateScrollToPage(1) } }, text = { Text(stringResource(R.string.provider_models)) })
                    }
                    HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { page ->
                        if (page == 0) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconPickerRow(providerIcon, { providerIcon = it }, text = providerInitials(name), onBusyChange = { iconImporting = it }, matchName = name)
                            ExposedDropdownMenuBox(typeExpanded, { typeExpanded = !typeExpanded }, modifier = Modifier.fillMaxWidth()) {
                                SelectableOutlinedTextField(type.displayName, {}, readOnly = true, label = { Text(stringResource(R.string.common_type)) },
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                                ExposedDropdownMenu(typeExpanded, { typeExpanded = false }) {
                                    ProviderType.entries.forEach { next -> RoundedDropdownMenuItem(text = { Text(next.displayName) }, onClick = {
                                        if (next != type) {
                                            val updated = initialProvider.copy(name = name, type = type, baseUrl = baseUrl).withType(next)
                                            name = updated.name; baseUrl = updated.baseUrl; type = updated.type
                                            val auth = next.defaultAuthorization()
                                            authMethod = auth.method; authName = auth.key.orEmpty(); apiKey = auth.value.orEmpty()
                                            oauthAuthorized = false; oauthIdentity = null; oauthMarker = ""; oauthAuthError = null
                                        }
                                        typeExpanded = false
                                    }) }
                                }
                            }
                            SelectableOutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.common_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            ProviderBaseUrlField(type, baseUrl, urlValid) { baseUrl = it }
                            if (baseUrl.trim().startsWith("http://", true)) Text(stringResource(R.string.http_unencrypted_provider_warning),
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                            if (authMethod in listOf(AuthMethod.BEARER_TOKEN, AuthMethod.QUERY_PARAM)) SelectableOutlinedTextField(authName, { authName = it },
                                label = { Text(if (authMethod == AuthMethod.BEARER_TOKEN) "Header key" else "Query parameter name") },
                                isError = !authValid, singleLine = true, modifier = Modifier.fillMaxWidth())
                            if (!type.isAccountProvider || authMethod != AuthMethod.OAUTH) ProviderAuthField(authMethod, apiKey, !type.isAccountProvider,
                                onMethodChange = { authMethod = it; authName = if (it == AuthMethod.QUERY_PARAM) "key" else "Authorization"; oauthAuthError = null },
                                onValueChange = { apiKey = it }, onFocusLost = { if (authMethod == AuthMethod.BEARER_TOKEN) apiKey = bearerHeaderValue(authName.trim().ifBlank { "Authorization" }, apiKey) })
                            if (authMethod == AuthMethod.OAUTH) OAuthAccountCard(signedIn = oauthAuthorized, identity = oauthIdentity,
                                signingIn = oauthAuthorizing && !oauthClearing, signingOut = oauthClearing, canSignIn = onAuthorizeProvider != null,
                                canSignOut = onClearOAuthCredentials != null, error = oauthAuthError,
                                onSignIn = rememberOAuthStart { scope.launch {
                                    oauthAuthorizing = true; oauthAuthError = null
                                    val request = draft().copy(baseUrl = baseUrl.trim().ifEmpty { type.defaultBaseUrl }, auth = Authorization(AuthMethod.OAUTH, key = oauthIdentity, value = oauthMarker))
                                    val result = onAuthorizeProvider?.invoke(request) ?: Result.failure(IllegalStateException("OAuth is unavailable for this provider."))
                                    result.onSuccess { authorized -> providerPersisted = true; oauthAuthorized = true; oauthIdentity = authorized.auth.key;
                                        oauthMarker = authorized.auth.value.orEmpty(); baseUrl = authorized.baseUrl
                                    }.onFailure { oauthAuthError = it.message ?: "OAuth authorization failed." }
                                    oauthAuthorizing = false
                                } }, onSignOut = { scope.launch {
                                    oauthAuthorizing = true; oauthClearing = true; oauthAuthError = null
                                    val result = onClearOAuthCredentials?.invoke(draft().copy(baseUrl = baseUrl.trim().ifEmpty { type.defaultBaseUrl }))
                                        ?: Result.failure(IllegalStateException("OAuth credential removal is unavailable."))
                                    result.onSuccess { oauthAuthorized = false; oauthIdentity = null; oauthMarker = "" }
                                        .onFailure { oauthAuthError = it.message ?: "Could not remove OAuth credentials." }
                                    oauthClearing = false; oauthAuthorizing = false
                                } })
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.stream), Modifier.weight(1f).padding(end = 12.dp))
                                Switch(supportStream, onCheckedChange = { supportStream = it })
                            }
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(R.string.custom_headers), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                IconButton(onClick = { headerRows.add("" to "") }) { Icon(Icons.Default.Add, stringResource(R.string.add_header)) }
                            }
                            headerRows.forEachIndexed { index, row -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SelectableOutlinedTextField(row.first, { headerRows[index] = it to headerRows[index].second }, label = { Text(stringResource(R.string.common_key)) }, singleLine = true, modifier = Modifier.weight(1f))
                                SelectableOutlinedTextField(row.second, { headerRows[index] = headerRows[index].first to it }, label = { Text(stringResource(R.string.common_value)) }, singleLine = true, modifier = Modifier.weight(1f))
                                IconButton(onClick = { headerRows.removeAt(index) }) { Icon(Icons.Outlined.Delete, stringResource(R.string.delete_header)) }
                            } }
                            if (!headersValid) Text("Header names and values must be valid and header names must be unique.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        } else ProviderModelsPane(order, models, modelList, onMove = { from, to -> order = order.moved(from, to) },
                            onDrop = { if (providerPersisted) onReorderModels(initialProvider.id, order) }, onEdit = { editingModel = it },
                            onDelete = { updateModels(models - it) }, onOpenCatalog = { showCatalog = true })
                    }
                }
            }
            if (showTestDialog) ModelConnectionDialog(draft().copy(name = name.trim().ifEmpty { "Provider" }), tests, onTestConnection != null,
                onRemoveModels = { updateModels(models - it) }, onDismiss = { showTestDialog = false })
            if (showCatalog) ProviderModelCatalogSheet(initialProvider.copy(name = name, type = type, baseUrl = baseUrl.trim(), auth = authorization(), config = requestConfig()),
                models.keys, onFetchModels, onCatalogLoaded = { discovered ->
                    catalog = discovered.associateBy { it.id }
                    updateModels(models.mapValues { (id, config) -> val metadata = catalog[id]
                        config.copy(contextWindowTokens = metadata?.contextWindowTokens ?: config.contextWindowTokens,
                            displayName = config.displayName.ifBlank { metadata?.displayName.orEmpty() }) })
                }, onToggle = { id -> updateModels(if (id in models) models - id else models + (id to memory.configurationFor(catalog[id] ?: DiscoveredModel(id)))) },
                onSetSelection = { ids, selected -> updateModels(if (selected) models + ids.associateWith { models[it] ?: memory.configurationFor(catalog[it] ?: DiscoveredModel(it)) } else models - ids.toSet()) },
                onDismiss = { showCatalog = false })
        }
    }
}
