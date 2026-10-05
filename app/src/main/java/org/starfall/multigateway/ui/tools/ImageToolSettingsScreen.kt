package org.starfall.multigateway.ui.tools

import org.starfall.multigateway.ui.components.AppAlertDialog as AlertDialog
import org.starfall.multigateway.ui.components.SelectableOutlinedTextField
import org.starfall.multigateway.ui.components.RoundedDropdownMenuItem as DropdownMenuItem

import androidx.activity.compose.BackHandler
import org.starfall.multigateway.ui.navigation.LocalScreenTransitionActive
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.*
import org.starfall.multigateway.data.model.*
import org.starfall.multigateway.data.tools.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageToolSettingsScreen(provider: LlmProviderInfo, config: SystemToolConfig, onSave: (JsonObject) -> Unit, onBack: () -> Unit) {
    val format = remember { Json { prettyPrint = true } }
    var draft by rememberSaveable(provider.id, config.modelId) { mutableStateOf(format.encodeToString(JsonObject.serializer(), config.imageOptions)) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val parsed = runCatching { Json.parseToJsonElement(draft).jsonObject }.getOrNull()
    val error = if (parsed == null) "Enter a valid JSON object." else runCatching {
        validateImageOptions(provider.type, config.modelId, parsed)
    }.exceptionOrNull()?.message
    val dirty = parsed != config.imageOptions
    fun back() { if (dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = LocalScreenTransitionActive.current) { back() }
    Scaffold(
        topBar = { TopAppBar(
            title = { Text("Image settings") },
            navigationIcon = { IconButton(onClick = { back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = { Button(enabled = error == null, onClick = { parsed?.let(onSave) }, modifier = Modifier.padding(end = 8.dp)) { Text("Save") } }
        ) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(provider.name + " / " + (provider.config.modelConfigs[config.modelId]?.displayName?.ifBlank { config.modelId } ?: config.modelId),
                    style = MaterialTheme.typography.titleMedium)
                Text("Only set options supported by this model. Empty fields use provider defaults. Settings are saved separately for each model.",
                    style = MaterialTheme.typography.bodySmall)
            }
            item { ToolSwitch("Advanced JSON", advanced) { advanced = it } }
            if (advanced) item {
                SelectableOutlinedTextField(
                    value = draft, onValueChange = { if (it.length <= 65536) draft = it },
                    label = { Text("Additional request body") },
                    supportingText = { Text("Nested objects and provider extensions are supported. Model and prompt come from chat.") },
                    isError = error != null, minLines = 8, maxLines = 18, modifier = Modifier.fillMaxWidth()
                )
            } else if (parsed != null) {
                items(imageOptionFields(provider.type, config.modelId), key = { it.path }) { field ->
                    ImageOptionInput(field, parsed.optionAt(field.path)) { value ->
                        draft = format.encodeToString(JsonObject.serializer(), parsed.withOption(field.path, value))
                    }
                }
            } else item { Text("Fix the JSON in Advanced JSON to use the form.", color = MaterialTheme.colorScheme.error) }
            error?.let { problem -> item { Text(problem, color = MaterialTheme.colorScheme.error) } }
            item { TextButton(onClick = { draft = "{}" }) { Text("Reset this model to defaults") } }
        }
    }
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Discard changes?") }, text = { Text("Your image settings have not been saved.") },
        confirmButton = { TextButton(onClick = onBack) { Text("Discard") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } }
    )
}

@Composable
private fun ImageOptionInput(field: ImageOptionField, value: JsonElement?, onChange: (JsonElement?) -> Unit) {
    val current = (value as? JsonPrimitive)?.takeUnless { it == JsonNull }?.content.orEmpty()
    var text by remember { mutableStateOf(current) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(current, focused) { if (!focused) text = current }
    var choosing by remember { mutableStateOf(false) }
    val choices = if (field.kind == "boolean") listOf("true", "false") else field.choices
    val invalid = text.isNotBlank() && when (field.kind) {
        "integer" -> text.toLongOrNull() == null
        "number" -> text.toDoubleOrNull()?.isFinite() != true
        else -> false
    }
    Column {
        SelectableOutlinedTextField(
            value = text,
            onValueChange = { input ->
                text = input
                if (input.isBlank()) onChange(null)
                else when (field.kind) {
                    "integer" -> onChange(input.toLongOrNull()?.let { JsonPrimitive(it) } ?: JsonPrimitive(input))
                    "number" -> onChange(input.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { JsonPrimitive(it) } ?: JsonPrimitive(input))
                    else -> onChange(JsonPrimitive(input))
                }
            },
            label = { Text(field.label) }, placeholder = { Text("Provider default") },
            readOnly = field.kind == "boolean", singleLine = true, isError = invalid,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            supportingText = if (field.min != null || field.max != null) {{ Text("Range: ${field.min ?: "…"} – ${field.max ?: "…"}") }} else null
        )
        if (choices.isNotEmpty()) Box {
            TextButton(onClick = { choosing = true }) { Text("Choose value") }
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                DropdownMenuItem(text = { Text("Provider default") }, onClick = { onChange(null); text = ""; choosing = false })
                choices.forEach { choice ->
                    DropdownMenuItem(text = { Text(choice) }, onClick = {
                        onChange(if (field.kind == "boolean") JsonPrimitive(choice.toBoolean()) else JsonPrimitive(choice))
                        text = choice; choosing = false
                    })
                }
            }
        }
    }
}
