package org.starfall.multigateway.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import org.starfall.multigateway.data.local.preferences.AppPreferences
import org.starfall.multigateway.ui.navigation.SlideScreenContent

enum class SettingsCategory(val title: String, val subtitle: String, val icon: ImageVector) {
    APPEARANCE("Appearance", "Theme, color palette & dynamic color", Icons.Outlined.Palette),
    PREFERENCES("Preferences", "Behavior, vibrations & display", Icons.Outlined.Tune),
    USER_DATA("Data & Storage", "Manage conversations, backup & wipe data", Icons.Outlined.Storage),
    UPDATE("Software Update", "Check the latest GitHub release", Icons.Outlined.SystemUpdateAlt),
    ABOUT("About MultiGateway", "Version, repository, license & app info", Icons.Outlined.Info)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    appPreferences: AppPreferences,
    conversationCount: Int,
    profileCount: Int,
    providerCount: Int,
    onThemeChange: (String) -> Unit,
    onAmoledChange: (Boolean) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onColorSchemeChange: (String) -> Unit,
    onContinueLastConversationChange: (Boolean) -> Unit,
    onPersistChatSelectionChange: (Boolean) -> Unit,
    onEnableVibrationChange: (Boolean) -> Unit,
    onHideStatusBarChange: (Boolean) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onLatexModeChange: (String) -> Unit,
    onClearAllConversations: () -> Unit,
    onResetAllData: () -> Unit,
    onBack: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    BackHandler(enabled = selectedCategory != null) {
        selectedCategory = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = selectedCategory?.title ?: "Settings",
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
                        // Main Settings Categories List
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(SettingsCategory.values().size) { idx ->
                                val cat = SettingsCategory.values()[idx]
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp,
                                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedCategory = cat }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(16.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Icon(
                                                    imageVector = cat.icon,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(22.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.width(16.dp))

                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = cat.title,
                                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            Text(
                                                text = cat.subtitle,
                                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                                color = MaterialTheme.colorScheme.outline
                                            )
                                        }

                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                }
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
                            onEnableVibrationChange = onEnableVibrationChange,
                            onHideStatusBarChange = onHideStatusBarChange,
                            onDebugModeChange = onDebugModeChange,
                            onLatexModeChange = onLatexModeChange
                        )
                    }

                    SettingsCategory.USER_DATA -> {
                        UserDataSettingsView(
                            conversationCount = conversationCount,
                            profileCount = profileCount,
                            providerCount = providerCount,
                            onClearAllConversations = onClearAllConversations,
                            onResetAllData = onResetAllData
                        )
                    }

                    SettingsCategory.UPDATE -> {
                        UpdateSettingsView()
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
fun AppearanceSettingsView(
    appPreferences: AppPreferences,
    onThemeChange: (String) -> Unit,
    onAmoledChange: (Boolean) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onColorSchemeChange: (String) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Text(
                text = "Theme Mode",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val modes = listOf("SYSTEM", "LIGHT", "DARK")
                modes.forEach { mode ->
                    val isSelected = appPreferences.themeMode == mode
                    FilterChip(
                        selected = isSelected,
                        onClick = { onThemeChange(mode) },
                        label = { Text(mode.lowercase().replaceFirstChar { it.uppercase() }) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AMOLED",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Use pure black surfaces when dark mode is active",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(
                    checked = appPreferences.useAmoled,
                    onCheckedChange = onAmoledChange
                )
            }
        }

        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Dynamic Color (Material Design)",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Extract color palette from device wallpaper (Android 12+)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                Switch(
                    checked = appPreferences.useDynamicColor,
                    onCheckedChange = onDynamicColorChange
                )
            }
        }

        if (!appPreferences.useDynamicColor) {
            item {
                Text(
                    text = "Color Palette",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(8.dp))
                val palettes = listOf(
                    "DEFAULT" to Color(0xFF0B57D0),
                    "EMERALD" to Color(0xFF006C4C),
                    "SUNSET" to Color(0xFFA04000),
                    "CRIMSON" to Color(0xFFB3261E),
                    "VIOLET" to Color(0xFF6B4FA0)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    palettes.forEach { (name, color) ->
                        val isSelected = appPreferences.colorSchemeName == name
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = color.copy(alpha = 0.2f),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) color else color.copy(alpha = 0.4f)
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .clickable { onColorSchemeChange(name) }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(color)
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
fun PreferencesSettingsView(
    appPreferences: AppPreferences,
    onContinueLastConversationChange: (Boolean) -> Unit,
    onPersistChatSelectionChange: (Boolean) -> Unit,
    onEnableVibrationChange: (Boolean) -> Unit,
    onHideStatusBarChange: (Boolean) -> Unit,
    onDebugModeChange: (Boolean) -> Unit,
    onLatexModeChange: (String) -> Unit
) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            PreferenceToggle(
                title = "Continue Last Chat",
                subtitle = "Automatically open the most recent conversation on launch",
                checked = appPreferences.continueLastConversation,
                onCheckedChange = onContinueLastConversationChange
            )
        }

        item {
            PreferenceToggle(
                title = "Persist Selection",
                subtitle = "Remember last selected model and profile across sessions",
                checked = appPreferences.persistChatSelection,
                onCheckedChange = onPersistChatSelectionChange
            )
        }

        item {
            PreferenceToggle(
                title = "Haptic Vibration",
                subtitle = "Vibrate on button taps and generation events",
                checked = appPreferences.enableVibration,
                onCheckedChange = onEnableVibrationChange
            )
        }

        item {
            PreferenceToggle(
                title = "Immersive Mode",
                subtitle = "Hide system status bar for distraction-free chatting",
                checked = appPreferences.hideStatusBar,
                onCheckedChange = onHideStatusBarChange
            )
        }

        item {
            PreferenceToggle(
                title = "Developer Debug Logs",
                subtitle = "Display token metrics, raw JSON payloads, and network logs",
                checked = appPreferences.debugMode,
                onCheckedChange = onDebugModeChange
            )
        }

        item {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }

        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "LaTeX Equation Rendering",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Render formulas in LaTeX format. Auto-detect parses equations but ignores price ranges like $10 to $20.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val modes = listOf("ON" to "Always On", "OFF" to "Disabled", "AUTO" to "Auto Detect")
                    modes.forEach { (mode, label) ->
                        val isSelected = appPreferences.latexMode == mode
                        FilterChip(
                            selected = isSelected,
                            onClick = { onLatexModeChange(mode) },
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PreferenceToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun UserDataSettingsView(
    conversationCount: Int,
    profileCount: Int,
    providerCount: Int,
    onClearAllConversations: () -> Unit,
    onResetAllData: () -> Unit
) {
    var showClearConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Data Overview",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        DataStatItem(label = "Chats", count = conversationCount.toString())
                        DataStatItem(label = "Profiles", count = profileCount.toString())
                        DataStatItem(label = "Providers", count = providerCount.toString())
                    }
                }
            }
        }

        item {
            OutlinedButton(
                onClick = { showClearConfirm = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.DeleteSweep, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Clear All Chat History")
            }
        }

        item {
            Button(
                onClick = { showResetConfirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.Warning, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Reset All Application Data")
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
            text = { Text("This will wipe all conversations, custom chat profiles, and provider credentials.") },
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
    val currentBuild = PackageInfoCompat.getLongVersionCode(packageInfo)
    var isChecking by remember { mutableStateOf(true) }
    var latestRelease by remember { mutableStateOf<GitHubReleaseInfo?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
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
        // Current Version Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Current version", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                Text("MultiGateway $currentVersion ($currentBuild)", style = MaterialTheme.typography.bodyLarge)
                Text("Package: ${context.packageName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }

        // Software Update Status Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Latest GitHub Release", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    IconButton(
                        onClick = { refreshKey++ },
                        enabled = !isChecking,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = "Refresh",
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
    val targetSdk = context.applicationInfo.targetSdkVersion

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "MultiGateway",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary
                )
                Text("Universal AI Gateway for Android", style = MaterialTheme.typography.bodyMedium)
                Text("Version $versionName ($versionCode)", style = MaterialTheme.typography.bodyMedium)
                Text("Target Android SDK $targetSdk", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Text("Package: ${context.packageName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/starfall-orb/multigateway"))
                    )
                }
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Project", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
                    Icon(
                        imageVector = Icons.Outlined.OpenInNew,
                        contentDescription = "Open Repo",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "Repository: starfall-orb/multigateway",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("License: Starfall Orb Contributor Commercial Copyleft License v1.0", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                Text("Tap to open repository on GitHub", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

