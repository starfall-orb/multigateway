package org.starfall.multigateway.ui.settings
import org.starfall.multigateway.ui.components.SelectableOutlinedTextField
import org.starfall.multigateway.ui.components.RoundedDropdownMenuItem as DropdownMenuItem
import org.starfall.multigateway.ui.components.windowHeightIn

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.starfall.multigateway.R
import org.starfall.multigateway.data.service.IconStore
import org.starfall.multigateway.data.service.StoredIcon
import org.starfall.multigateway.ui.components.EntityIcon

@Composable
fun IconSettingsView() {
    val context = LocalContext.current
    val store = remember(context) { IconStore(context) }
    val scope = rememberCoroutineScope()
    val revision by IconStore.revision.collectAsState()
    val entries by produceState(emptyList<StoredIcon>(), revision) {
        value = withContext(Dispatchers.IO) { store.entries() }
    }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<StoredIcon?>(null) }
    var patterns by remember { mutableStateOf(emptyList<String>()) }
    var pickingDark by remember { mutableStateOf(false) }
    fun edit(entry: StoredIcon) {
        editing = entry
        patterns = entry.patterns
    }
    fun mutate(action: () -> Unit) {
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { action() }
                editing = null
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Toast.makeText(context, R.string.icon_cache_save_failed, Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            try {
                val entry = withContext(Dispatchers.IO) {
                    val image = store.importImage(uri)
                    store.entries().first { it.image == image }
                }
                edit(entry)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                Toast.makeText(context, R.string.icon_import_failed, Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    val variantPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val entry = editing
        val dark = pickingDark
        if (uri != null && entry != null) scope.launch {
            busy = true
            try {
                editing = withContext(Dispatchers.IO) {
                    val replacement = store.importImage(uri, shared = false)
                    store.setVariant(entry.image, replacement, dark)
                    store.entries().first { it.image == entry.image }
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) {
                Toast.makeText(context, R.string.icon_import_failed, Toast.LENGTH_LONG).show()
            } finally { busy = false }
        }
    }
    val visible = entries.filter { entry ->
        entry.filename.contains(query.trim(), ignoreCase = true) ||
            entry.image.contains(query.trim(), ignoreCase = true) ||
            entry.patterns.any { it.contains(query.trim(), ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsCard {
            SelectableOutlinedTextField(value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.icon_cache_search)) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.icon_settings_title), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall)
                IconButton(enabled = !busy, onClick = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Icon(Icons.Outlined.AddPhotoAlternate, stringResource(R.string.icon_cache_add)) }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            if (visible.isEmpty()) item {
                SettingsCard { Text(stringResource(if (query.isBlank()) R.string.icon_cache_empty else R.string.icon_cache_no_results), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(visible, key = { it.image }) { entry ->
                var menu by remember { mutableStateOf(false) }
                Surface(shape = RoundedCornerShape(24.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EntityIcon(entry.image, Modifier.size(48.dp))
                        Column(Modifier.weight(1f)) {
                            Text(entry.filename, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleSmall)
                            Text(entry.patterns.joinToString(" · ").ifEmpty { stringResource(R.string.icon_cache_no_matches) },
                                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                        }
                        Box {
                            IconButton(enabled = !busy, onClick = { menu = true }) {
                                Icon(Icons.Outlined.MoreVert, stringResource(R.string.icon_cache_actions))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.icon_cache_edit)) },
                                    onClick = { menu = false; edit(entry) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) },
                                    onClick = { menu = false; mutate { store.delete(entry.image) } })
                            }
                        }
                    }
                }
            }
        }
    }
    editing?.let { entry ->
        val valid = patterns.all { it.isNotBlank() && runCatching { Regex(it) }.isSuccess }
        AlertDialog(onDismissRequest = { if (!busy) editing = null },
            title = { Text(stringResource(R.string.icon_cache_edit)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EntityIcon(entry.image, Modifier.size(48.dp))
                        Text(entry.filename, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        listOf(false, true).forEach { dark ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(stringResource(if (dark) R.string.icon_variant_dark else R.string.icon_variant_normal),
                                    style = MaterialTheme.typography.labelMedium)
                                EntityIcon(if (dark) entry.darkImage else entry.lightImage, Modifier.size(48.dp),
                                    useDarkVariant = false,
                                    backgroundColor = if (dark) Color(0xFF202024) else Color(0xFFF4F4F4))
                                TextButton(enabled = !busy, onClick = {
                                    pickingDark = dark
                                    variantPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                }) { Text(stringResource(R.string.choose_icon)) }
                                if (dark && entry.darkImage != null) IconButton(enabled = !busy, onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            editing = withContext(Dispatchers.IO) {
                                                store.setVariant(entry.image, null, true)
                                                store.entries().first { it.image == entry.image }
                                            }
                                        } catch (error: CancellationException) { throw error
                                        } catch (_: Exception) {
                                            Toast.makeText(context, R.string.icon_cache_save_failed, Toast.LENGTH_LONG).show()
                                        } finally { busy = false }
                                    }
                                }) { Icon(Icons.Outlined.Close, stringResource(R.string.remove_icon)) }
                            }
                        }
                    }
                    LazyColumn(Modifier.windowHeightIn(maxFraction = 0.4f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(patterns.size) { index ->
                            val pattern = patterns[index]
                            val invalid = pattern.isBlank() || runCatching { Regex(pattern) }.isFailure
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SelectableOutlinedTextField(value = pattern, onValueChange = { value ->
                                    patterns = patterns.toMutableList().apply { this[index] = value }
                                }, modifier = Modifier.weight(1f), enabled = !busy,
                                    label = { Text(stringResource(R.string.icon_cache_regex)) }, isError = invalid,
                                    supportingText = if (invalid) ({ Text(stringResource(R.string.icon_cache_invalid_regex)) }) else null)
                                IconButton(enabled = !busy, onClick = {
                                    patterns = patterns.filterIndexed { i, _ -> i != index }
                                }) { Icon(Icons.Outlined.Close, stringResource(R.string.icon_cache_delete)) }
                            }
                        }
                    }
                    TextButton(enabled = !busy, onClick = { patterns = patterns + "" }) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text(stringResource(R.string.icon_cache_add_match))
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = valid && !busy, onClick = {
                    val updated = patterns.toList()
                    mutate { store.editMatches(entry.image, updated) }
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { editing = null }) {
                Text(stringResource(R.string.common_cancel))
            } })
    }
}
