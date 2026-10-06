package org.starfall.multigateway.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.starfall.multigateway.R
import org.starfall.multigateway.data.service.FaviconResult
import org.starfall.multigateway.data.service.FaviconService
import org.starfall.multigateway.data.service.IconStore

@Composable
internal fun FaviconDialog(baseUrl: String, onSave: (String) -> Unit, onBusyChange: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { FaviconService(context) }
    val store = remember(context) { IconStore(context) }
    var homepage by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<FaviconResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val currentBusy by rememberUpdatedState(onBusyChange)
    DisposableEffect(Unit) { onDispose { currentBusy(false) } }
    AppAlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.favicon_get_logo)) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (busy) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
                val preview = result
                if (preview != null) {
                    Image(preview.bitmap.asImageBitmap(), contentDescription = stringResource(R.string.favicon_preview),
                        modifier = Modifier.size(96.dp).align(Alignment.CenterHorizontally))
                    Text(preview.url.host + preview.url.encodedPath, style = MaterialTheme.typography.bodySmall)
                } else if (error != null) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                } else {
                    SelectableOutlinedTextField(homepage, { homepage = it }, enabled = !busy,
                        label = { Text(stringResource(R.string.favicon_homepage_url)) },
                        supportingText = { Text(stringResource(R.string.favicon_homepage_help)) },
                        singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            if (result != null) TextButton(enabled = !busy, onClick = {
                val bitmap = result?.bitmap ?: return@TextButton
                scope.launch {
                    busy = true; saving = true; currentBusy(true)
                    try {
                        val id = withContext(Dispatchers.IO) { store.importBitmap(bitmap) }
                        onSave(id)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { result = null; error = "Could not save the logo: ${failure.message.orEmpty()}" }
                    finally { busy = false; saving = false; currentBusy(false) }
                }
            }) { Text(stringResource(R.string.common_save)) }
            else if (error == null) TextButton(enabled = !busy, onClick = {
                scope.launch {
                    busy = true; currentBusy(true)
                    try { result = service.get(homepage, baseUrl) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Could not retrieve a favicon." }
                    finally { busy = false; currentBusy(false) }
                }
            }) { Text(stringResource(R.string.favicon_get)) }
        },
        dismissButton = {
            Row {
                if (result != null || error != null) TextButton(enabled = !busy, onClick = { result = null; error = null }) {
                    Text(stringResource(R.string.favicon_reset))
                }
                TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
            }
        }
    )
}
