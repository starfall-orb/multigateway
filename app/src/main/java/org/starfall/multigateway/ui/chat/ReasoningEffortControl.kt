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

internal val reasoningEfforts = listOf<String?>("none", null, "low", "medium", "high", "xhigh")
internal val reasoningEffortLabels = listOf("Off", "Default", "Low", "Med", "High", "X-High")

internal fun reasoningEffortEnabled(effort: String?): Boolean =
    effort?.trim()?.lowercase() !in listOf("none", "off")

internal fun reasoningEffortIndex(effort: String?): Int =
    if (!reasoningEffortEnabled(effort)) 0 else reasoningEfforts.indexOf(effort?.trim()?.lowercase()).takeIf { it >= 0 } ?: 1

/** Changes only the conversation override; model capabilities remain untouched. */
@Composable
internal fun ReasoningEffortControl(effort: String?, onEffortChange: (String?) -> Unit) {
    val enabled = reasoningEffortEnabled(effort)
    var sliderValue by remember(effort) {
        mutableFloatStateOf(reasoningEffortIndex(effort).toFloat())
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Slider(
                value = sliderValue,
                onValueChange = {
                    sliderValue = it
                },
                onValueChangeFinished = {
                    onEffortChange(reasoningEfforts[sliderValue.roundToInt().coerceIn(reasoningEfforts.indices)])
                },
                valueRange = 0f..5f,
                steps = 4,
                modifier = Modifier.fillMaxWidth().testTag("reasoning-slider")
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                reasoningEffortLabels.forEach { label ->
                    Text(label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
