package org.starfall.multigateway.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import org.starfall.multigateway.R
import org.starfall.multigateway.data.local.preferences.AppPreferences
import org.starfall.multigateway.data.local.preferences.WordWrapMode
import org.starfall.multigateway.ui.components.SelectableOutlinedTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CodeRenderingPreferences(
    preferences: AppPreferences,
    onModeChange: (WordWrapMode) -> Unit,
    onColumnChange: (Int) -> Unit,
    onPreviewChange: (Boolean) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val labels = mapOf(
        WordWrapMode.OFF to stringResource(R.string.word_wrap_off),
        WordWrapMode.VIEWPORT to stringResource(R.string.word_wrap_viewport),
        WordWrapMode.COLUMN to stringResource(R.string.word_wrap_column),
        WordWrapMode.BOUNDED to stringResource(R.string.word_wrap_bounded)
    )
    ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
        SelectableOutlinedTextField(
            value = labels.getValue(preferences.wordWrapMode), onValueChange = {}, readOnly = true,
            label = { Text(stringResource(R.string.word_wrap)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor().testTag("word-wrap-mode")
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            labels.forEach { (mode, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { onModeChange(mode); expanded = false })
            }
        }
    }
    if (preferences.wordWrapMode == WordWrapMode.COLUMN || preferences.wordWrapMode == WordWrapMode.BOUNDED) {
        var column by remember(preferences.wordWrapColumn) { mutableStateOf(preferences.wordWrapColumn.toString()) }
        val parsed = column.toIntOrNull()?.takeIf { it > 0 }
        SelectableOutlinedTextField(
            value = column,
            onValueChange = { text ->
                if (text.all(Char::isDigit)) {
                    column = text
                    text.toIntOrNull()?.takeIf { it > 0 }?.let(onColumnChange)
                }
            },
            label = { Text(stringResource(R.string.word_wrap_column_count)) },
            supportingText = { Text(stringResource(if (parsed == null) R.string.word_wrap_column_invalid else R.string.word_wrap_column_hint)) },
            isError = parsed == null, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("word-wrap-column")
        )
    }
    PreferenceToggle(stringResource(R.string.code_preview), stringResource(R.string.code_preview_hint),
        preferences.codePreviewEnabled, onPreviewChange)
}
