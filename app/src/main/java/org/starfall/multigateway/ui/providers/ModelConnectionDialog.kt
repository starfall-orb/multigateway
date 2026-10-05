package org.starfall.multigateway.ui.providers

import org.starfall.multigateway.ui.components.AppAlertDialog as AlertDialog

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
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
    val removals = remember(provider.id) {
        ModelConnectionRemovals((provider.config.modelIds ?: provider.config.modelConfigs.keys.toList())
            .filter { provider.config.modelConfigs[it]?.modelType == ModelType.TEXT_GENERATION })
    }
    val textModels = removals.modelIds
    val testableModels = textModels.filterNot { it in removals.pending }
    val testingAny = tests.batchRunning.value || textModels.any { tests.running[it] == true }
    val unavailable = textModels.filter { tests.results[it]?.isFailure == true && it !in removals.pending }.toSet()
    val hasRemovals = removals.pending.isNotEmpty()
    fun close() = removals.close(remove = { ids -> tests.forget(ids); onRemoveModels(ids) }, dismiss = onDismiss)
    AlertDialog(onDismissRequest = ::close,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.test_connection), Modifier.weight(1f))
                IconButton(onClick = { tests.testAll(provider, testableModels) },
                    enabled = testableModels.isNotEmpty() && !testingAny && available) {
                    Icon(Icons.Outlined.NetworkCheck, "Test all text models")
                }
                IconButton(onClick = { if (hasRemovals) removals.restoreAll() else removals.mark(unavailable) },
                    enabled = hasRemovals || unavailable.isNotEmpty()) {
                    Icon(if (hasRemovals) Icons.Outlined.Restore else Icons.Outlined.DeleteSweep,
                        stringResource(if (hasRemovals) R.string.restore_removed_models else R.string.remove_unavailable_models),
                        tint = if (hasRemovals) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        },
        text = {
            LazyColumn(Modifier.fillMaxWidth().windowHeightIn(maxFraction = 0.75f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (textModels.isEmpty()) item { Text("No text generation models") }
                items(textModels, key = { it }) { id ->
                    val testing = tests.running[id] == true
                    val removed = id in removals.pending
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
                                IconButton(enabled = !removed && !testingAny && available, onClick = { tests.test(provider, id) }) {
                                    if (testing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Outlined.NetworkCheck, stringResource(R.string.test_model, id))
                                }
                                IconButton(onClick = { removals.toggle(id) }) {
                                    Icon(if (removed) Icons.Outlined.Restore else Icons.Outlined.Delete,
                                        stringResource(if (removed) R.string.restore_model else R.string.delete_model, id),
                                        tint = if (removed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                }
                            }
                            if (removed) Text(stringResource(R.string.model_removed_on_dialog_close),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
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
        }, confirmButton = { TextButton(onClick = ::close) { Text(stringResource(R.string.common_close)) } })
}
