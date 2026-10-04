package org.starfall.multigateway.ui.providers

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.Box
import org.starfall.multigateway.R
import org.starfall.multigateway.data.model.AuthMethod
import org.starfall.multigateway.data.model.ProviderType
import org.starfall.multigateway.ui.components.RoundedDropdownMenuItem

private fun AuthMethod.legacyDisplayName(): String = when (this) {
    AuthMethod.PLATFORM_DEFAULT -> "Platform Default"
    AuthMethod.NONE, AuthMethod.OTHER -> "None"
    AuthMethod.BEARER_TOKEN, AuthMethod.CUSTOM_HEADER -> "Bearer Token"
    AuthMethod.QUERY_PARAM -> "URL Query"
    AuthMethod.OAUTH -> "OAuth Flow"
}

private fun AuthMethod.displayNameFallback(): String = when (this) {
    AuthMethod.BEARER_TOKEN -> "Bearer Token"
    AuthMethod.CUSTOM_HEADER -> "Custom Header"
    else -> legacyDisplayName()
}

@Composable
fun AuthMethod.displayName(): String = when (this) {
    AuthMethod.BEARER_TOKEN -> stringResource(R.string.provider_auth_bearer_type)
    AuthMethod.CUSTOM_HEADER -> stringResource(R.string.provider_auth_custom_header)
    else -> displayNameFallback()
}

@Composable
internal fun ProviderBaseUrlField(type: ProviderType, value: String, valid: Boolean, onChange: (String) -> Unit) {
    val defaultUrl = if (type == ProviderType.OPENAI) "https://api.openai.com/v1" else type.defaultBaseUrl
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(stringResource(R.string.provider_base_url)) },
        isError = !valid,
        supportingText = if (!valid) ({ Text(stringResource(R.string.provider_invalid_base_url)) }) else null,
        trailingIcon = {
            IconButton(onClick = { onChange(defaultUrl) }) {
                Icon(Icons.Outlined.Language, stringResource(R.string.provider_restore_default_url))
            }
        },
        singleLine = true, modifier = Modifier.fillMaxWidth()
    )
}

@Composable
internal fun ProviderAuthField(
    method: AuthMethod,
    value: String,
    canSelect: Boolean,
    onMethodChange: (AuthMethod) -> Unit,
    onValueChange: (String) -> Unit,
    onFocusLost: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var revealed by remember(method) { mutableStateOf(false) }
    val hasKey = method !in listOf(AuthMethod.OAUTH, AuthMethod.NONE, AuthMethod.OTHER)
    OutlinedTextField(
        value = if (hasKey) value else "", onValueChange = onValueChange,
        label = {
            Text(
                if (hasKey) stringResource(R.string.provider_api_key_label, method.displayName())
                else method.displayName()
            )
        }, readOnly = !hasKey,
        leadingIcon = if (canSelect) ({
            Box {
                IconButton(onClick = { expanded = true }) {
                    Icon(
                        if (expanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                        stringResource(R.string.provider_select_auth_type)
                    )
                }
                DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                    listOf(AuthMethod.PLATFORM_DEFAULT, AuthMethod.BEARER_TOKEN, AuthMethod.QUERY_PARAM, AuthMethod.OAUTH, AuthMethod.NONE).forEach { option ->
                        RoundedDropdownMenuItem(text = { Text(option.displayName()) }, onClick = {
                            onMethodChange(option)
                            expanded = false
                        })
                    }
                }
            }
        }) else null,
        trailingIcon = if (hasKey) ({
            IconButton(onClick = { revealed = !revealed }) {
                Icon(if (revealed) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                    stringResource(if (revealed) R.string.provider_hide_api_key else R.string.provider_show_api_key))
            }
        }) else null,
        visualTransformation = if (revealed || !hasKey) VisualTransformation.None else PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().onFocusChanged { if (!it.isFocused) onFocusLost() }
    )
}
