package org.starfall.multigateway.ui.chat

import org.starfall.multigateway.ui.components.AppBottomSheet
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.starfall.multigateway.data.model.ConversationSummaryRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReasoningEffortSheet(
    currentEffort: String?,
    onApply: (String?) -> Unit,
    onDismiss: () -> Unit
) {
    val efforts = listOf<String?>("none", null, "low", "medium", "high", "xhigh")
    val labels = listOf("Off", "Auto", "Low", "Medium", "High", "Extra high")
    var index by remember(currentEffort) {
        mutableFloatStateOf(efforts.indexOf(currentEffort).takeIf { it >= 0 }?.toFloat() ?: 1f)
    }
    val selected = index.roundToInt().coerceIn(efforts.indices)

    AppBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Reasoning Effort", style = MaterialTheme.typography.titleLarge)
            Text(
                "Controls the reasoning effort for this conversation. This overrides the model setting only for this chat.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(labels[selected], style = MaterialTheme.typography.headlineSmall)
            Slider(
                value = index,
                onValueChange = { index = it },
                valueRange = 0f..5f,
                steps = 4
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                labels.forEach { label ->
                    Text(label.take(1), style = MaterialTheme.typography.labelSmall)
                }
            }
            Button(
                onClick = {
                    onApply(efforts[selected])
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Apply")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationSummarySheet(
    onStart: (ConversationSummaryRequest) -> Boolean,
    onDismiss: () -> Unit
) {
    var targetSlider by remember { mutableFloatStateOf(1200f) }
    var customTarget by remember { mutableStateOf(false) }
    var customTargetText by remember { mutableStateOf("1200") }
    var chunked by remember { mutableStateOf(false) }
    var chunkSlider by remember { mutableFloatStateOf(20_000f) }
    var customChunk by remember { mutableStateOf(false) }
    var customChunkText by remember { mutableStateOf("20000") }
    var error by remember { mutableStateOf<String?>(null) }

    val targetTokens = if (customTarget) customTargetText.toIntOrNull() else targetSlider.roundToInt()
    val chunkTokens = if (customChunk) customChunkText.toIntOrNull() else chunkSlider.roundToInt()
    val valid = targetTokens != null && targetTokens in 1..6000 &&
        (!chunked || (chunkTokens != null && chunkTokens in 1..100_000))

    AppBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Conversation Summary", style = MaterialTheme.typography.titleLarge)
            Text(
                "Older messages remain visible, but after summarizing only the summary and messages after it are sent to the model.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Token counts are estimates based on text length, not the exact tokenizer of each model.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text("Target summary size: ${targetTokens ?: 0} tokens", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = targetSlider,
                onValueChange = { targetSlider = it },
                valueRange = 1f..6000f,
                enabled = !customTarget
            )
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = customTarget, onCheckedChange = { customTarget = it })
                Text("Custom target")
            }
            if (customTarget) {
                OutlinedTextField(
                    value = customTargetText,
                    onValueChange = { customTargetText = it.filter(Char::isDigit) },
                    label = { Text("Target tokens") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Text("Summarization mode", style = MaterialTheme.typography.titleMedium)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !chunked,
                    onClick = { chunked = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    label = { Text("All at once") }
                )
                SegmentedButton(
                    selected = chunked,
                    onClick = { chunked = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    label = { Text("Multiple passes") }
                )
            }

            if (chunked) {
                Text("Token limit / pass: ${chunkTokens ?: 0}", style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = chunkSlider,
                    onValueChange = { chunkSlider = it },
                    valueRange = 1f..100_000f,
                    enabled = !customChunk
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = customChunk, onCheckedChange = { customChunk = it })
                    Text("Custom per-pass limit")
                }
                if (customChunk) {
                    OutlinedTextField(
                        value = customChunkText,
                        onValueChange = { customChunkText = it.filter(Char::isDigit) },
                        label = { Text("Tokens / pass") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (error != null) {
                Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = {
                    val request = ConversationSummaryRequest(
                        targetTokens = targetTokens ?: 1,
                        chunked = chunked,
                        tokensPerChunk = chunkTokens ?: 20_000
                    )
                    if (onStart(request)) onDismiss()
                    else error = "Configure a Chat Summary text model in Default Models, or stop the current generation first."
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Start summary")
            }
        }
    }
}
