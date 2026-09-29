package org.starfall.multigateway.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.starfall.multigateway.data.model.*

@Composable
fun ModelConfigDialog(
    provider: LlmProviderInfo,
    modelId: String,
    onSave: (ModelConfiguration) -> Unit,
    onDismiss: () -> Unit
) {
    val config = provider.config.modelConfigs[modelId] ?: ModelConfiguration()
    var temperature by remember { mutableStateOf(config.temperature?.toString().orEmpty()) }
    var topP by remember { mutableStateOf(config.topP?.toString().orEmpty()) }
    var topK by remember { mutableStateOf(config.topK?.toString().orEmpty()) }
    var supportStream by remember { mutableStateOf(config.supportStream) }
    val supportsTopK = !provider.type.isOpenAi
    val tempMax = if (provider.type == ProviderType.ANTHROPIC) 1.0 else 2.0
    val tempValid = temperature.isBlank() || temperature.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..tempMax } == true
    val topPValid = topP.isBlank() || topP.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..1.0 } == true
    val topKValid = !supportsTopK || topK.isBlank() || topK.toIntOrNull()?.let { it > 0 } == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(modelId, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(provider.name, style = MaterialTheme.typography.labelLarge)
                Text("Stream responses", style = MaterialTheme.typography.titleSmall)
                Column(Modifier.fillMaxWidth().selectableGroup()) {
                    listOf<Boolean?>(null, true, false).forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                .selectable(selected = supportStream == option, role = Role.RadioButton,
                                    onClick = { supportStream = option }).padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = supportStream == option, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(when (option) {
                                null -> "Use provider setting (${if (provider.config.supportStream) "On" else "Off"})"
                                true -> "On"
                                false -> "Off"
                            }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                HorizontalDivider()
                Text("Leave blank to use API defaults. These settings apply only to this model on this provider.")
                OutlinedTextField(temperature, { temperature = it }, label = { Text("Temperature (0–$tempMax)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { if (!tempValid) Text("Enter a number from 0 to $tempMax, or leave blank.") },
                    isError = !tempValid, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(topP, { topP = it }, label = { Text("Top-p (0–1)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { if (!topPValid) Text("Enter a number from 0 to 1, or leave blank.") },
                    isError = !topPValid, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (supportsTopK) {
                    OutlinedTextField(topK, { topK = it }, label = { Text("Top-k (positive integer)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        supportingText = { if (!topKValid) Text("Enter a positive whole number, or leave blank.") },
                        isError = !topKValid, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                TextButton(onClick = { temperature = ""; topP = ""; topK = "" }) { Text("Reset sampling to API defaults") }
            }
        },
        confirmButton = {
            Button(enabled = tempValid && topPValid && topKValid, onClick = {
                onSave(config.copy(temperature = temperature.toDoubleOrNull(), topP = topP.toDoubleOrNull(),
                    topK = if (supportsTopK) topK.toIntOrNull() else null, supportStream = supportStream))
                onDismiss()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
