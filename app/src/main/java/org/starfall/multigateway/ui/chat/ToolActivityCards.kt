package org.starfall.multigateway.ui.chat

import org.starfall.multigateway.ui.components.AppBottomSheet
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import org.starfall.multigateway.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.starfall.multigateway.data.model.ToolActivity
import org.starfall.multigateway.data.tools.ToolFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sin

private val toolJson = Json { prettyPrint = true }

@Composable
fun ToolActivityCards(activities: List<ToolActivity>) {
    var selectedActivityId by remember { mutableStateOf<String?>(null) }
    val visibleActivities = activities.filterNot { it.name.endsWith(": connect") || it.name == "send_file" }
    val selectedActivity = selectedActivityId?.let { id -> visibleActivities.find { it.id == id } }

    if (visibleActivities.isNotEmpty()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            visibleActivities.forEach { activity ->
                key(activity.id) {
                    ToolActivityLabel(
                        activity = activity,
                        onClick = { selectedActivityId = activity.id }
                    )
                }
            }
        }
    }

    selectedActivity?.let { activity ->
        ToolDetailsSheet(
            activity = activity,
            onDismiss = { selectedActivityId = null }
        )
    }
}

@Composable
private fun ToolActivityLabel(
    activity: ToolActivity,
    onClick: () -> Unit
) {
    val running = activity.status == "running"
    val phase = if (running) {
        val transition = rememberInfiniteTransition(label = "tool-running")
        val wavePhase by transition.animateFloat(
            initialValue = 0f,
            targetValue = (Math.PI * 2).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "tool-alpha"
        )
        wavePhase
    } else {
        0f
    }
    val color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val displayName = when (activity.name) {
        "generate_image" -> stringResource(if (running) R.string.tool_generating_image else R.string.tool_generate_image)
        "generate_video" -> stringResource(if (running) R.string.tool_generating_video else R.string.tool_generate_video)
        else -> activity.name.replace('_', ' ')
    }
    val label = buildAnnotatedString {
        displayName.forEachIndexed { index, character ->
            val alpha = if (running) 0.4f + 0.6f * ((sin(phase - index * 0.5f) + 1f) / 2f) else 1f
            withStyle(SpanStyle(color = color.copy(alpha = alpha))) { append(character) }
        }
    }

    Text(
        text = label,
        style = if (running) MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp) else MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 7.dp, bottom = 7.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolDetailsSheet(
    activity: ToolActivity,
    onDismiss: () -> Unit
) {
    var argsTab by remember(activity.id) { mutableStateOf(0) }
    val context = LocalContext.current
    val revision by ToolFiles.revision.collectAsState()
    val jsonFile = (activity.responseFile ?: activity.files.firstOrNull { it.endsWith(".json", ignoreCase = true) })?.removePrefix("tool-file:")
    val savedJson by produceState<String?>(null, jsonFile, revision) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                jsonFile?.let { ToolFiles(context).resolve(it) }?.reader()?.use { reader ->
                    val buffer = CharArray(64_001)
                    var count = 0
                    while (count < buffer.size) {
                        val read = reader.read(buffer, count, buffer.size - count)
                        if (read < 0) break
                        count += read
                    }
                    String(buffer, 0, minOf(count, 64_000)) +
                        if (count > 64_000) "\n\n[JSON preview truncated]" else ""
                }
            }.getOrNull()
        }
    }
    val response = savedJson ?: activity.response.ifBlank { activity.summary }

    AppBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Text(
                text = activity.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.height(24.dp))
            Text(
                text = "Args",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))

            TabRow(
                selectedTabIndex = argsTab,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                divider = {}
            ) {
                Tab(
                    selected = argsTab == 0,
                    onClick = { argsTab = 0 },
                    text = { Text("Pretty") }
                )
                Tab(
                    selected = argsTab == 1,
                    onClick = { argsTab = 1 },
                    text = { Text("Raw") }
                )
            }

            Spacer(Modifier.height(10.dp))
            if (argsTab == 0) {
                PrettyArguments(activity.arguments)
            } else {
                RawValueBlock(activity.arguments.ifBlank { "{}" })
            }

            Spacer(Modifier.height(26.dp))
            Text(
                text = "Response",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(10.dp))
            if (jsonFile != null) {
                Text(jsonFile, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
            }
            RawValueBlock(
                value = response.ifBlank {
                    if (activity.status == "running") "Waiting for tool response…" else "No response"
                }
            )
        }
    }
}

@Composable
private fun PrettyArguments(raw: String) {
    val parsed = remember(raw) { parseJsonObject(raw) }
    if (parsed == null || parsed.isEmpty()) {
        RawValueBlock(raw.ifBlank { "{}" })
        return
    }

    val borderColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
    ) {
        parsed.entries.forEachIndexed { index, (key, value) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .weight(0.36f)
                        .border(0.5.dp, borderColor)
                        .padding(horizontal = 10.dp, vertical = 11.dp)
                ) {
                    Text(
                        text = key,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(0.64f)
                        .border(0.5.dp, borderColor)
                        .padding(horizontal = 10.dp, vertical = 11.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = displayJsonValue(value),
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 18.sp
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RawValueBlock(value: String) {
    val context = LocalContext.current
    val label = if (looksLikeJson(value)) "json" else "text"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("Tool details", value))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.outline
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                SelectionContainer {
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 17.sp
                        ),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

private fun parseJsonObject(raw: String): JsonObject? = runCatching {
    Json.parseToJsonElement(raw.ifBlank { "{}" }).jsonObject
}.getOrNull()

private fun displayJsonValue(value: JsonElement): String = when (value) {
    is JsonPrimitive -> if (value.isString) value.content else value.toString()
    else -> runCatching { toolJson.encodeToString(JsonElement.serializer(), value) }.getOrDefault(value.toString())
}

private fun looksLikeJson(value: String): Boolean = runCatching {
    Json.parseToJsonElement(value)
}.isSuccess
