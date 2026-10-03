package org.starfall.multigateway.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.starfall.multigateway.R
import org.starfall.multigateway.data.service.IconRule
import org.starfall.multigateway.data.service.IconStore
import org.starfall.multigateway.ui.components.EntityIcon

@Composable
fun IconSettingsView() {
    val context = LocalContext.current
    val store = remember(context) { IconStore(context) }
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf(store.rules()) }
    var importing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<String?>(null) }
    fun update(updated: List<IconRule>) { rules = updated; saved = false }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            val editing = target
            scope.launch {
                importing = true
                try {
                    val image = withContext(Dispatchers.IO) { store.importImage(uri) }
                    update(if (editing == null) listOf(IconRule(pattern = "", image = image)) + rules
                        else rules.map { if (it.id == editing) it.copy(image = image) else it })
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    Toast.makeText(context, R.string.icon_import_failed, Toast.LENGTH_LONG).show()
                } finally { importing = false }
            }
        }
    }
    val busy = importing || saving
    val valid = rules.all { it.pattern.isNotBlank() && runCatching { Regex(it.pattern) }.isSuccess }
    val chooseDescription = stringResource(R.string.choose_icon)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.icon_cache_help), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(if (saved) stringResource(R.string.icon_cache_saved) else stringResource(R.string.icon_cache_rules),
                modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            IconButton(enabled = !busy, onClick = {
                target = null
                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Icon(Icons.Outlined.AddPhotoAlternate, stringResource(R.string.icon_cache_add)) }
            TextButton(enabled = valid && !busy, onClick = {
                scope.launch {
                    saving = true
                    try {
                        withContext(Dispatchers.IO) { store.saveRules(rules) }
                        saved = true
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        Toast.makeText(context, R.string.icon_cache_save_failed, Toast.LENGTH_LONG).show()
                    } finally { saving = false }
                }
            }) { Text(stringResource(R.string.icon_cache_save)) }
        }
        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 16.dp)) {
            if (rules.isEmpty()) item { Text(stringResource(R.string.icon_cache_empty)) }
            items(rules, key = { it.id }) { rule ->
                val error = rule.pattern.isBlank() || runCatching { Regex(rule.pattern) }.isFailure
                Surface(shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Row(Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        EntityIcon(rule.image, Modifier.size(56.dp)
                            .semantics { contentDescription = chooseDescription }
                            .clickable(enabled = !busy) {
                                target = rule.id
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            })
                        OutlinedTextField(value = rule.pattern, onValueChange = { pattern ->
                            update(rules.map { if (it.id == rule.id) it.copy(pattern = pattern) else it })
                        }, enabled = !busy, modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.icon_cache_regex)) },
                            isError = error,
                            supportingText = if (error) ({ Text(stringResource(R.string.icon_cache_invalid_regex)) }) else null)
                        IconButton(enabled = !busy, onClick = { update(rules.filterNot { it.id == rule.id }) }) {
                            Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.icon_cache_delete))
                        }
                    }
                }
            }
        }
    }
}
