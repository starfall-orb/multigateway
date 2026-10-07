package org.starfall.multigateway.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.roundToInt
import org.starfall.multigateway.data.model.ModelConfiguration
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.data.model.reasoningBudget
import org.starfall.multigateway.data.model.claudeThinkingCapabilities

internal val reasoningEfforts = listOf<String?>("none", null, "low", "medium", "high", "xhigh")
internal val reasoningEffortLabels = listOf("Off", "Default", "Low", "Medium", "High", "X-High")

internal data class ReasoningEffortOption(val effort: String?, val label: String)

internal fun usesThinkingBudget(providerType: ProviderType?, modelId: String): Boolean =
    (providerType in listOf(ProviderType.ANTHROPIC, ProviderType.CLAUDE_CODE) &&
        claudeThinkingCapabilities(modelId).efforts.isEmpty()) ||
        (providerType == ProviderType.ANTIGRAVITY && modelId.contains("claude", ignoreCase = true))

internal fun reasoningEffortOptions(
    providerType: ProviderType? = null,
    modelId: String = "",
    maxTokens: Int = Int.MAX_VALUE
): List<ReasoningEffortOption> {
    if (providerType in listOf(ProviderType.ANTHROPIC, ProviderType.CLAUDE_CODE)) {
        val capabilities = claudeThinkingCapabilities(modelId)
        if (capabilities.efforts.isNotEmpty()) {
            val off = when {
                capabilities.canDisable -> listOf(ReasoningEffortOption("none", "Off"))
                capabilities.betweenTools -> listOf(ReasoningEffortOption("none", "Between tools"))
                else -> emptyList()
            }
            return off + ReasoningEffortOption(null, "Default") + capabilities.efforts.map { effort ->
                ReasoningEffortOption(effort, when (effort) {
                    "xhigh" -> "X-High"
                    "max" -> "Max"
                    else -> effort.replaceFirstChar { it.uppercase() }
                })
            }
        }
    }
    val budgetBased = usesThinkingBudget(providerType, modelId)
    val gemini = providerType == ProviderType.GOOGLE ||
        (providerType == ProviderType.ANTIGRAVITY && !budgetBased)
    return reasoningEfforts.mapIndexedNotNull { index, effort ->
        if (gemini && effort == "xhigh") return@mapIndexedNotNull null
        val label = when {
            budgetBased && index >= 2 -> ModelConfiguration(reasoningEffort = effort)
                .reasoningBudget(maxTokens.coerceAtLeast(2)).toString()
            providerType == ProviderType.OLLAMA && effort == "xhigh" -> "Maximum"
            else -> reasoningEffortLabels[index]
        }
        ReasoningEffortOption(effort, label)
    }
}

internal fun reasoningEffortEnabled(effort: String?): Boolean =
    effort?.trim()?.lowercase() !in listOf("none", "off")

internal fun reasoningEffortIndex(
    effort: String?,
    options: List<ReasoningEffortOption> = reasoningEffortOptions()
): Int {
    if (!reasoningEffortEnabled(effort)) {
        return options.indexOfFirst { it.effort == "none" }.takeIf { it >= 0 }
            ?: options.indexOfFirst { it.effort == "low" }.takeIf { it >= 0 } ?: 0
    }
    val normalized = effort?.trim()?.lowercase()
    val index = options.indexOfFirst { it.effort == normalized }
    if (index >= 0) return index
    val defaultIndex = options.indexOfFirst { it.effort == null }.coerceAtLeast(0)
    // Preserve saved maximum overrides when switching between model capabilities.
    return if (normalized == "xhigh") {
        options.indexOfFirst { it.effort == "max" }.takeIf { it >= 0 }
            ?: options.indexOfFirst { it.effort == "high" }.takeIf { it >= 0 } ?: defaultIndex
    } else if (normalized == "max") {
        options.indexOfFirst { it.effort == "xhigh" }.takeIf { it >= 0 }
            ?: options.indexOfFirst { it.effort == "high" }.takeIf { it >= 0 } ?: defaultIndex
    } else defaultIndex
}

/** Changes only the conversation override; model capabilities remain untouched. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReasoningEffortControl(
    effort: String?,
    onEffortChange: (String?) -> Unit,
    onPreviewValueChange: (Float) -> Unit = {},
    options: List<ReasoningEffortOption> = reasoningEffortOptions(),
    onColorBurst: (Offset, Boolean) -> Unit = { _, _ -> }
) {
    var sliderValue by remember(effort, options) {
        mutableFloatStateOf(reasoningEffortIndex(effort, options).toFloat())
    }
    val maximumValue = options.lastIndex.toFloat()
    val highValue = options.indexOfFirst { it.effort == "high" }
    val layoutDirection = LocalLayoutDirection.current
    val interactionSource = remember { MutableInteractionSource() }
    val dragged by interactionSource.collectIsDraggedAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    val thumbScale by animateFloatAsState(if (dragged || pressed) 1.1f else 1f,
        tween(160), label = "thinking-thumb-scale")
    val burst = rememberThinkingColorBurst()
    var trackBounds by remember { mutableStateOf(Rect.Zero) }
    fun colorBand(value: Float) = when {
        value.roundToInt() >= options.lastIndex -> 2
        value.roundToInt() >= highValue -> 1
        else -> 0
    }
    val band = colorBand(sliderValue)
    var previousColorBand by remember { mutableIntStateOf(band) }
    val tint by animateFloatAsState(if (band > 0) 1f else 0f,
        tween(750, easing = FastOutSlowInEasing), label = "thinking-track-tint")
    val leftColor by animateColorAsState(if (band == 2) Color(0xFF4A1018) else Color(0xFFE9D5FF),
        tween(750), label = "thinking-track-left")
    val rightColor by animateColorAsState(if (band == 2) Color(0xFF160406) else Color(0xFF7C3AED),
        tween(750), label = "thinking-track-right")
    val thumbColor by animateColorAsState(when (band) {
        2 -> Color(0xFFB91C1C)
        1 -> Color(0xFF7C3AED)
        else -> MaterialTheme.colorScheme.primary
    }, tween(500), label = "thinking-thumb-color")
    val trackColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.primaryContainer
    val sliderColors = SliderDefaults.colors(thumbColor = thumbColor)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Slider(
                value = sliderValue,
                onValueChange = {
                    val previousBand = colorBand(sliderValue)
                    sliderValue = it
                    onPreviewValueChange(it)
                    val nextBand = colorBand(it)
                    if (nextBand > 0 && nextBand != previousBand && trackBounds.width > 0f) {
                        previousColorBand = previousBand
                        val fraction = (it / maximumValue).coerceIn(0f, 1f)
                        val physicalFraction = if (layoutDirection == LayoutDirection.Rtl) 1f - fraction else fraction
                        val origin = Offset(trackBounds.width * physicalFraction, trackBounds.height / 2f)
                        burst.start(origin, if (nextBand == 2) Color(0xFFF43F5E) else Color(0xFFA78BFA))
                        onColorBurst(trackBounds.topLeft + origin, nextBand == 2)
                    }
                },
                onValueChangeFinished = {
                    onEffortChange(options[sliderValue.roundToInt().coerceIn(options.indices)].effort)
                },
                valueRange = 0f..maximumValue,
                steps = options.size - 2,
                colors = sliderColors,
                interactionSource = interactionSource,
                thumb = {
                    Canvas(
                        modifier = Modifier.size(28.dp).graphicsLayer {
                            val pulse = burst.progress.value
                            val scale = thumbScale + 0.12f * (1f - pulse)
                            scaleX = scale
                            scaleY = scale
                        }.shadow(3.dp, CircleShape)
                    ) {
                        drawCircle(
                            Brush.radialGradient(
                                listOf(lerp(thumbColor, Color.White, 0.28f), thumbColor),
                                center = center - Offset(3.dp.toPx(), 3.dp.toPx()),
                                radius = size.minDimension
                            )
                        )
                    }
                },
                track = {
                    Box(Modifier.fillMaxWidth().height(12.dp)
                        .onGloballyPositioned {
                            trackBounds = Rect(it.positionInRoot(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
                        }
                        .clip(CircleShape).drawBehind {
                            val fraction = (sliderValue / maximumValue).coerceIn(0f, 1f)
                            val width = size.width * fraction
                            val radius = CornerRadius(size.height / 2f)
                            drawRoundRect(inactiveColor, cornerRadius = radius)
                            drawRoundRect(trackColor,
                                topLeft = Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f, 0f),
                                size = Size(width, size.height), cornerRadius = radius)
                            if (burst.progress.value < 1f && previousColorBand > 0) {
                                val previousColors = if (previousColorBand == 2)
                                    listOf(Color(0xFF4A1018), Color(0xFF160406))
                                else listOf(Color(0xFFE9D5FF), Color(0xFF7C3AED))
                                drawRoundRect(Brush.horizontalGradient(previousColors),
                                    cornerRadius = radius, alpha = tint)
                            }
                            revealThinkingColor(burst) {
                                drawRoundRect(Brush.horizontalGradient(listOf(leftColor, rightColor)),
                                    cornerRadius = radius, alpha = tint)
                            }
                            // Subtle detents remain visible beneath the settling color.
                            repeat(options.size) { index ->
                                val x = size.width * index / options.lastIndex
                                drawCircle(Color.White.copy(alpha = 0.32f), 1.25.dp.toPx(), Offset(x, size.height / 2f))
                            }
                            drawThinkingColorBurst(burst)
                        })
                },
                modifier = Modifier.fillMaxWidth().testTag("reasoning-slider")
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                options.forEach { option ->
                    Text(option.label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Physical left-to-right colors: the strongest purple stays on the right. */
internal fun maximumThinkingCardBrush(surface: Color): Brush = Brush.horizontalGradient(
    listOf(lerp(surface, Color(0xFF7C3AED), 0.16f), lerp(surface, Color(0xFF7C3AED), 0.48f))
)
