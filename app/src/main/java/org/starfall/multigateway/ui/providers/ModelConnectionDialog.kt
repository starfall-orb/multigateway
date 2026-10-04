package org.starfall.multigateway.ui.providers

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.starfall.multigateway.R
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.ui.components.windowHeightIn

@Composable
internal fun ModelConnectionDialog(provider: LlmProviderInfo, tests: ModelConnectionTests, available: Boolean,
    onRemoveModels: (Set<String>) -> Unit, onDismiss: () -> Unit) {
    val textModels = (provider.config.modelIds ?: provider.config.modelConfigs.keys.toList())
        .filter { provider.config.modelConfigs[it]?.modelType == ModelType.TEXT_GENERATION }
    val testingAny = textModels.any { tests.running[it] == true }
    val unavailable = textModels.filter { tests.results[it]?.isFailure == true && tests.running[it] != true }.toSet()
    fun remove(ids: Set<String>) { onRemoveModels(ids); tests.forget(ids) }
    AlertDialog(onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.test_connection), Modifier.weight(1f))
                IconButton(onClick = { textModels.forEach { tests.test(provider, it) } },
                    enabled = textModels.isNotEmpty() && !testingAny && available) {
                    Icon(Icons.Outlined.NetworkCheck, "Test all text models")
                }
                IconButton(onClick = { remove(unavailable) }, enabled = unavailable.isNotEmpty() && !testingAny) {
                    Icon(Icons.Outlined.DeleteSweep, "Remove unavailable models", tint = MaterialTheme.colorScheme.error)
                }
            }
        },
        text = {
            LazyColumn(Modifier.fillMaxWidth().windowHeightIn(maxFraction = 0.75f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (textModels.isEmpty()) item { Text("No text generation models") }
                items(textModels, key = { it }) { id ->
                    val testing = tests.running[id] == true
                    val result = tests.results[id]
                    val config = provider.config.modelConfigs[id] ?: ModelConfiguration()
                    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(config.displayName.ifBlank { id }, fontWeight = FontWeight.SemiBold)
                                    if (config.displayName.isNotBlank()) Text(id, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                IconButton(enabled = !testing && available, onClick = { tests.test(provider, id) }) {
                                    if (testing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Outlined.NetworkCheck, stringResource(R.string.test_model, id))
                                }
                                IconButton(enabled = !testing, onClick = { remove(setOf(id)) }) {
                                    Icon(Icons.Outlined.Delete, stringResource(R.string.delete_model, id), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                            result?.let { tested ->
                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.Top) {
                                    Icon(if (tested.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error, null,
                                        tint = if (tested.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(tested.getOrNull() ?: tested.exceptionOrNull()?.localizedMessage ?: stringResource(R.string.connection_failed),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (tested.isSuccess) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } })
}
