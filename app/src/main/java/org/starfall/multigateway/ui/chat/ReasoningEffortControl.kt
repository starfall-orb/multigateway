package org.starfall.multigateway.ui.chat

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal val reasoningEfforts = listOf<String?>(null, "low", "medium", "high", "xhigh")
internal val reasoningEffortLabels = listOf("Default", "Low", "Med", "High", "X-High")

internal fun reasoningEffortEnabled(effort: String?): Boolean =
    effort?.trim()?.lowercase() !in listOf("none", "off")

internal fun reasoningEffortIndex(effort: String?): Int =
    reasoningEfforts.indexOf(effort?.trim()?.lowercase()).coerceAtLeast(0)

/** Changes only the conversation override; model capabilities remain untouched. */
@Composable
internal fun ReasoningEffortControl(effort: String?, onEffortChange: (String?) -> Unit) {
    val enabled = reasoningEffortEnabled(effort)
    var lastEnabledEffort by remember { mutableStateOf(effort.takeIf { enabled }) }
    LaunchedEffect(effort) {
        if (enabled) lastEnabledEffort = effort
    }
    var sliderValue by remember(effort, lastEnabledEffort) {
        mutableFloatStateOf(reasoningEffortIndex(if (enabled) effort else lastEnabledEffort).toFloat())
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
            checked = enabled,
            onCheckedChange = { onEffortChange(if (it) lastEnabledEffort else "none") },
            modifier = Modifier.testTag("reasoning-enabled")
                .semantics { contentDescription = "Enable reasoning for this conversation" }
        )
        Column(Modifier.weight(1f)) {
            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                    val selected = reasoningEfforts[it.roundToInt().coerceIn(reasoningEfforts.indices)]
                    lastEnabledEffort = selected
                    onEffortChange(selected)
                },
                enabled = enabled,
                valueRange = 0f..4f,
                steps = 3,
                modifier = Modifier.fillMaxWidth().testTag("reasoning-slider")
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                reasoningEffortLabels.forEach { label ->
                    Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f))
                }
            }
        }
    }
}
