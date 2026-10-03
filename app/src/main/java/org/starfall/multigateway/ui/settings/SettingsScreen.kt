package org.starfall.multigateway.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import org.starfall.multigateway.data.local.preferences.AppPreferences
import org.starfall.multigateway.ui.navigation.SlideScreenContent
import org.starfall.multigateway.ui.theme.ThemePresets

enum class SettingsCategory(val title: String, val subtitle: String, val icon: ImageVector) {
    APPEARANCE("Appearance", "Theme, color palette & dynamic color", Icons.Outlined.Palette),
    PREFERENCES("Preferences", "Behavior, vibrations & display", Icons.Outlined.Tune),
    ICONS("Icons & Cache", "Manage shared icons and matching rules", Icons.Outlined.Image),
    USER_DATA("App Data", "Conversation history & application reset", Icons.Outlined.ManageHistory),
    ABOUT("About", "App information, software updates & license", Icons.Outlined.Info)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appPreferences: AppPreferences,
    conversationCount: Int,
    providerCount: Int,
    onThemeChange: (String) -> Unit,
    onAmoledChange: (Boolean) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onColorSchemeChange: (String) -> Unit,
    onContinueLastConversationChange: (Boolean) -> Unit,
    onPersistChatSelectionChange: (Boolean) -> Unit,
    onAutoScrollChange: (Boolean) -> Unit,
    onEnableVibrationChange: (Boolean) -> Unit,
    onHideStatusBarChange: (Boolean) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onLatexModeChange: (String) -> Unit,
    onClearAllConversations: () -> Unit,
    onResetAllData: () -> Unit,
    onBack: () -> Unit,
    onNavigateToProviders: () -> Unit = {},
    onNavigateToMcp: () -> Unit = {},
    onNavigateToSpeech: () -> Unit = {},
    onNavigateToSystemTools: () -> Unit = {},
    onNavigateToStorage: () -> Unit = {}
) {
    var selectedCategory by rememberSaveable { mutableStateOf<SettingsCategory?>(null) }
    BackHandler(enabled = selectedCategory != null) {
        selectedCategory = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (selectedCategory == SettingsCategory.ICONS) androidx.compose.ui.res.stringResource(org.starfall.multigateway.R.string.icon_settings_title) else selectedCategory?.title ?: "General Settings",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selectedCategory != null) {
                                selectedCategory = null
                            } else {
                                onBack()
                            }
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            SlideScreenContent(editor = selectedCategory, label = "Settings category") { category ->
                when (category) {
                    null -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                SettingsGroupHeading("Configuration")
                            }
                            item { SettingsNavigationItem("Default Models", "Image, video & conversation helpers", Icons.Outlined.Build, onNavigateToSystemTools) }
                            item { SettingsNavigationItem("Providers", "AI providers, models & API connections", Icons.Outlined.CloudQueue, onNavigateToProviders) }
                            item { SettingsNavigationItem("MCP Manage", "Tool servers & integrations", Icons.Outlined.Extension, onNavigateToMcp) }
                            item { SettingsNavigationItem("Speech Services", "Text-to-speech providers & voices", Icons.Outlined.RecordVoiceOver, onNavigateToSpeech) }
                            item { SettingsNavigationItem("Storage", "Files created by your tools", Icons.Outlined.FolderOpen, onNavigateToStorage) }
                            item {
                                Spacer(Modifier.height(8.dp))
                                SettingsGroupHeading("System Settings")
                            }
                            items(SettingsCategory.entries.size) { index ->
                                val category = SettingsCategory.entries[index]
                                SettingsNavigationItem(
                                    title = if (category == SettingsCategory.ICONS) androidx.compose.ui.res.stringResource(org.starfall.multigateway.R.string.icon_settings_title) else category.title,
                                    subtitle = if (category == SettingsCategory.ICONS) androidx.compose.ui.res.stringResource(org.starfall.multigateway.R.string.icon_settings_description) else category.subtitle,
                                    icon = category.icon,
                                    onClick = { selectedCategory = category }
                                )
                            }
                        }
                    }

                    SettingsCategory.APPEARANCE -> {
                        AppearanceSettingsView(
                            appPreferences = appPreferences,
                            onThemeChange = onThemeChange,
                            onAmoledChange = onAmoledChange,
                            onDynamicColorChange = onDynamicColorChange,
                            onColorSchemeChange = onColorSchemeChange
                        )
                    }

                    SettingsCategory.PREFERENCES -> {
                        PreferencesSettingsView(
                            appPreferences = appPreferences,
                            onContinueLastConversationChange = onContinueLastConversationChange,
                            onPersistChatSelectionChange = onPersistChatSelectionChange,
                            onAutoScrollChange = onAutoScrollChange,
                            onEnableVibrationChange = onEnableVibrationChange,
                            onHideStatusBarChange = onHideStatusBarChange,
                            onDebugModeChange = onDebugModeChange,
                            onLatexModeChange = onLatexModeChange
                        )
                    }

                    SettingsCategory.ICONS -> IconSettingsView()

                    SettingsCategory.USER_DATA -> {
                        UserDataSettingsView(
                            conversationCount = conversationCount,
                            providerCount = providerCount,
                            onClearAllConversations = onClearAllConversations,
                            onResetAllData = onResetAllData
                        )
                    }

                    SettingsCategory.ABOUT -> {
                        AboutSettingsView()
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroupHeading(title: String) {
    Text(title, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceSettingsView(
    appPreferences: AppPreferences,
    onThemeChange: (String) -> Unit,
    onAmoledChange: (Boolean) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onColorSchemeChange: (String) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SettingsSection("Theme") {
                Text("Choose how MultiGateway looks", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("SYSTEM", "LIGHT", "DARK").forEach { mode ->
                        FilterChip(selected = appPreferences.themeMode == mode, onClick = { onThemeChange(mode) },
                            label = { Text(mode.lowercase().replaceFirstChar { it.uppercase() }) }, modifier = Modifier.weight(1f))
                    }
                }
                PreferenceToggle("AMOLED", "Use pure black surfaces in dark mode", appPreferences.useAmoled, onAmoledChange)
            }
        }
        item {
            SettingsSection("Color") {
                PreferenceToggle("Dynamic Color", "Use colors from your wallpaper on Android 12 and above",
                    appPreferences.useDynamicColor, onDynamicColorChange)
                if (!appPreferences.useDynamicColor) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Text("Color palette", style = MaterialTheme.typography.titleMedium)
                    androidx.compose.foundation.layout.FlowRow(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        ThemePresets.forEach { preset ->
                            val name = preset.name
                            val color = preset.preview
                            val selected = appPreferences.colorSchemeName == name
                            Surface(shape = RoundedCornerShape(16.dp), color = color.copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp,
                                    if (selected) color else color.copy(alpha = 0.3f)),
                                modifier = Modifier.width(58.dp).height(56.dp)
                                    .toggleable(selected, role = Role.RadioButton, onValueChange = { onColorSchemeChange(name) })
                                    .then(Modifier.semantics { contentDescription = name.lowercase().replaceFirstChar { it.uppercase() } })
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(26.dp).clip(CircleShape).background(color))
                                    if (selected) Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PreferencesSettingsView(
    appPreferences: AppPreferences,
    onContinueLastConversationChange: (Boolean) -> Unit,
    onPersistChatSelectionChange: (Boolean) -> Unit,
    onAutoScrollChange: (Boolean) -> Unit,
    onEnableVibrationChange: (Boolean) -> Unit,
    onHideStatusBarChange: (Boolean) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onLatexModeChange: (String) -> Unit
) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SettingsSection("Conversations") {
                PreferenceToggle("Continue Last Chat", "Open the most recent conversation on launch",
                    appPreferences.continueLastConversation, onContinueLastConversationChange)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                PreferenceToggle("Persist Selection", "Remember your selected model across sessions",
                    appPreferences.persistChatSelection, onPersistChatSelectionChange)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                PreferenceToggle("Auto scroll", "Follow new responses as they generate",
                    appPreferences.autoScroll, onAutoScrollChange)
            }
        }
        item {
            SettingsSection("Feedback & display") {
                PreferenceToggle("Haptic Vibration", "Vibrate on taps and generation events",
                    appPreferences.enableVibration, onEnableVibrationChange)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                PreferenceToggle("Immersive Mode", "Hide the status bar while chatting",
                    appPreferences.hideStatusBar, onHideStatusBarChange)
            }
        }
        item {
            SettingsSection("Rendering") {
                Text("LaTeX Equation Rendering", style = MaterialTheme.typography.titleMedium)
                Text("Auto-detect renders formulas while leaving prices as plain text.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("ON" to "Always On", "OFF" to "Disabled", "AUTO" to "Auto Detect").forEach { (mode, label) ->
                        FilterChip(selected = appPreferences.latexMode == mode, onClick = { onLatexModeChange(mode) }, label = { Text(label) })
                    }
                }
            }
        }
        item {
            SettingsSection("Developer") {
                PreferenceToggle("Developer Debug Logs", "Show token metrics, request payloads and network logs",
                    appPreferences.debugMode, onDebugModeChange)
            }
        }
    }
}

@Composable
fun PreferenceToggle(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
fun UserDataSettingsView(
    conversationCount: Int,
    providerCount: Int,
    onClearAllConversations: () -> Unit,
    onResetAllData: () -> Unit
) {
    var showClearConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            SettingsSection("Data overview") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    DataStatItem("Chats", conversationCount.toString())
                    DataStatItem("Providers", providerCount.toString())
                }
            }
        }
        item {
            SettingsSection("Manage data") {
                Text("Control the conversations and configuration stored on this device.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = { showClearConfirm = true }, shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.DeleteSweep, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Clear All Chat History")
                }
                OutlinedButton(onClick = { showResetConfirm = true }, shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Warning, null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Reset All Application Data")
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear All Chats") },
            text = { Text("Are you sure you want to delete all conversations? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        onClearAllConversations()
                        showClearConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            }
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Factory Reset Data") },
            text = { Text("This will wipe all conversations and provider credentials.") },
            confirmButton = {
                Button(
                    onClick = {
                        onResetAllData()
                        showResetConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Reset Everything")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
fun DataStatItem(label: String, count: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = count, style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
    }
}

private data class ApkAsset(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String
)

private data class GitHubReleaseInfo(
    val tagName: String,
    val title: String,
    val body: String,
    val pageUrl: String,
    val apkAssets: List<ApkAsset>
)

private suspend fun fetchLatestGitHubRelease(): GitHubReleaseInfo = withContext(Dispatchers.IO) {
    val connection = (URL("https://api.github.com/repos/starfall-orb/multigateway/releases/latest").openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 15_000
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        setRequestProperty("User-Agent", "MultiGateway-Android")
    }
    try {
        val code = connection.responseCode
        if (code !in 200..299) error("GitHub returned HTTP $code")
        val root = Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
        val tag = root["tag_name"]?.jsonPrimitive?.content.orEmpty()
        val title = root["name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: tag
        val body = root["body"]?.jsonPrimitive?.content.orEmpty()
        val page = root["html_url"]?.jsonPrimitive?.content.orEmpty()
        val assetsArray = root["assets"]?.jsonArray.orEmpty()

        val apkAssets = assetsArray
            .mapNotNull { it.jsonObject }
            .filter { it["name"]?.jsonPrimitive?.content?.endsWith(".apk", ignoreCase = true) == true }
            .map { asset ->
                val name = asset["name"]?.jsonPrimitive?.content.orEmpty()
                val size = asset["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val url = asset["browser_download_url"]?.jsonPrimitive?.content.orEmpty()
                ApkAsset(name, size, url)
            }

        GitHubReleaseInfo(tag, title, body, page, apkAssets)
    } finally {
        connection.disconnect()
    }
}

private fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(java.util.Locale.US, "%.1f MB", mb)
}

private fun compareVersions(current: String, latestTag: String): Int {
    fun parts(value: String): List<Int> = value
        .removePrefix("v")
        .split('.')
        .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    val a = parts(current)
    val b = parts(latestTag)
    val size = maxOf(a.size, b.size)
    repeat(size) { index ->
        val av = a.getOrElse(index) { 0 }
        val bv = b.getOrElse(index) { 0 }
        if (av != bv) return av.compareTo(bv)
    }
    return 0
}

@Composable
fun UpdateSettingsView() {
    val context = LocalContext.current
    val packageInfo = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val currentVersion = packageInfo.versionName.orEmpty()
    var isChecking by remember { mutableStateOf(false) }
    var latestRelease by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        if (refreshKey == 0) return@LaunchedEffect
        isChecking = true
        errorMessage = null
        runCatching { fetchLatestGitHubRelease() }
            .onSuccess { latestRelease = it }
            .onFailure { errorMessage = it.localizedMessage ?: "Unable to check GitHub releases" }
        isChecking = false
    }

    val release = latestRelease
    val updateAvailable = release?.let { compareVersions(currentVersion, it.tagName) < 0 } == true

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Software Update", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.primary)
                    IconButton(
                        onClick = { refreshKey++ },
                        enabled = !isChecking,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Check for updates",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                when {
                    isChecking -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Checking GitHub Releases...", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    errorMessage != null -> {
                        Text(errorMessage.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { refreshKey++ }) { Text("Try again") }
                    }
                    release != null -> {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(release.title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold))
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = if (updateAvailable) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                            ) {
                                Text(
                                    text = if (updateAvailable) "Update Available (${release.tagName})" else "Up to date (${release.tagName})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (updateAvailable) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }

                        // Release Notes Preview
                        if (release.body.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Release Notes",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .padding(12.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    org.starfall.multigateway.ui.chat.MarkdownRenderer(content = release.body)
                                }
                            }
                        }

                        if (updateAvailable) {
                            // APK Downloads List
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Download APK Variants",
                                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold)
                            )

                            if (release.apkAssets.isNotEmpty()) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    release.apkAssets.forEach { apkAsset ->
                                        val formattedSize = formatFileSize(apkAsset.sizeBytes)
                                        OutlinedButton(
                                            onClick = {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(apkAsset.downloadUrl)))
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Outlined.FileDownload,
                                                        contentDescription = "Download APK",
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = apkAsset.name,
                                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                                                        maxLines = 1,
                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                    )
                                                }
                                                if (formattedSize.isNotBlank()) {
                                                    Spacer(modifier = Modifier.width(8.dp))
                                                    Text(
                                                        text = formattedSize,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.outline
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            } else {
                                Button(
                                    onClick = {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.pageUrl)))
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Open GitHub Release Page")
                                }
                            }
                        }
                    }
                    else -> {
                        Text("Find the latest MultiGateway release on GitHub.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FilledTonalButton(onClick = { refreshKey++ }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.SystemUpdateAlt, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Check for updates")
                        }
                    }
                }
            }
        }
}

@Composable
fun AboutSettingsView() {
    val context = LocalContext.current
    val packageInfo = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val versionName = packageInfo.versionName.orEmpty()
    val versionCode = PackageInfoCompat.getLongVersionCode(packageInfo)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(60.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Hub, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(32.dp))
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("MultiGateway", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text("Version $versionName ($versionCode)", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Universal AI Gateway for Android", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        UpdateSettingsView()
        SettingsNavigationItem("Source code", "starfall-orb/multigateway", Icons.Outlined.Code) {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/starfall-orb/multigateway")))
        }
        SettingsSection("License") {
            Text("Starfall Orb Contributor Commercial Copyleft License v1.0", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
