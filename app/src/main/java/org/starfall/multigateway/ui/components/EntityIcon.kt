package org.starfall.multigateway.ui.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import coil3.compose.AsyncImage
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.starfall.multigateway.R
import org.starfall.multigateway.data.service.IconStore

internal fun providerInitials(name: String): String = name.trim()
    .split(Regex("\\s+"))
    .filter { it.isNotBlank() }
    .take(2)
    .mapNotNull { it.firstOrNull()?.uppercaseChar()?.toString() }
    .joinToString("")
    .ifBlank { "?" }

@Composable
fun EntityIcon(
    image: String?,
    modifier: Modifier = Modifier.size(42.dp),
    text: String? = null,
    fallback: ImageVector? = null,
    matchName: String? = null,
    model: Boolean = false,
    useDarkVariant: Boolean? = null,
    backgroundColor: Color? = null
) {
    val context = LocalContext.current
    val revision by IconStore.lookupRevision.collectAsState()
    val dark = useDarkVariant ?: (MaterialTheme.colorScheme.background.luminance() < 0.5f)
    var data by remember(context, image, matchName, model, dark) { mutableStateOf<Any?>(null) }
    var explicitFailed by remember(image, matchName, dark, revision) { mutableStateOf(false) }
    LaunchedEffect(image, matchName, model, revision, context, dark, explicitFailed) {
        data = withContext(Dispatchers.IO) {
            val store = IconStore(context)
            (if (explicitFailed) null else store.imageData(image, dark))
                ?: store.imageData(matchName?.let { store.resolve(it, model) }, dark)
        }
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp),
        color = backgroundColor ?: MaterialTheme.colorScheme.surfaceContainerHighest) {
        Box(contentAlignment = Alignment.Center) {
            if (data == null) {
                if (fallback != null) Icon(fallback, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxSize().padding(9.dp))
                else Text(text?.takeIf { it.isNotBlank() } ?: "?",
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            } else AsyncImage(data, contentDescription = null,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                onError = { if (image != null && !explicitFailed) explicitFailed = true })
        }
    }
}

@Composable
fun IconPickerRow(image: String?, onChange: (String?) -> Unit, text: String? = null, fallback: ImageVector? = null, onBusyChange: (Boolean) -> Unit = {}, matchName: String? = null, model: Boolean = false, faviconBaseUrl: String = "", defaultImage: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnBusy by rememberUpdatedState(onBusyChange)
    var importing by remember { mutableStateOf(false) }
    var showFavicon by remember { mutableStateOf(false) }
    if (showFavicon) FaviconDialog(faviconBaseUrl, onSave = { id ->
        currentOnChange(id); showFavicon = false
    }, onBusyChange = { importing = it; currentOnBusy(it) }, onDismiss = { showFavicon = false })
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            importing = true
            currentOnBusy(true)
            try {
                val id = withContext(Dispatchers.IO) { IconStore(context).importImage(uri, shared = false) }
                currentOnChange(id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Toast.makeText(context, R.string.icon_import_failed, Toast.LENGTH_LONG).show()
            } finally { importing = false; currentOnBusy(false) }
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        EntityIcon(image ?: defaultImage, Modifier.size(56.dp), text, fallback, matchName, model)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.entity_icon), style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !importing, onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    Icon(Icons.Outlined.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (importing) R.string.icon_importing else R.string.choose_icon))
                }
                IconButton(enabled = !importing, onClick = { showFavicon = true }) {
                    Icon(Icons.Outlined.Download, stringResource(R.string.favicon_get_logo))
                }
            }
        }
        if (image != null) IconButton(enabled = !importing, onClick = { currentOnChange(null) }) {
            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.remove_icon))
        }
    }
}
