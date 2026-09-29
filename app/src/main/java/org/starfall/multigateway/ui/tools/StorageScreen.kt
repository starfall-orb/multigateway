package org.starfall.multigateway.ui.tools

import android.content.Intent
import android.graphics.BitmapFactory
import android.widget.MediaController
import android.widget.VideoView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.starfall.multigateway.data.tools.ToolFiles
import java.io.File

private fun File.mime() = when(extension.lowercase()) { "png"->"image/png"; "jpg"->"image/jpeg"; "gif"->"image/gif"; "webp"->"image/webp"; "mp4"->"video/mp4"; "webm"->"video/webm"; "txt"->"text/plain"; else->"application/octet-stream" }
private suspend fun thumbnail(file: File, size: Int) = withContext(Dispatchers.IO) {
    runCatching {
        val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
        BitmapFactory.decodeFile(file.path,bounds)
        var sample=1
        while(bounds.outWidth/sample>size || bounds.outHeight/sample>size) sample*=2
        BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=sample})?.asImageBitmap()
    }.getOrNull()
}
private data class ToolFilesSnapshot(val files: List<File>, val totalBytes: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(store: ToolFiles, onBack: () -> Unit) {
    val revision by ToolFiles.revision.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(ToolFilesSnapshot(emptyList(), 0L)) }
    val files = snapshot.files
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val allSelected = files.isNotEmpty() && selected.size == files.size

    LaunchedEffect(revision) {
        snapshot = withContext(Dispatchers.IO) {
            val current = store.list()
            ToolFilesSnapshot(current, current.sumOf { it.length() })
        }
        selected = selected.intersect(snapshot.files.mapTo(mutableSetOf()) { it.name })
    }

    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Storage", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "storage-summary") {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Tool files", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${files.size} files · ${android.text.format.Formatter.formatFileSize(context, snapshot.totalBytes)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (files.isNotEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    selected = if (allSelected) emptySet() else files.mapTo(mutableSetOf()) { it.name }
                                }) {
                                    Text(if (allSelected) "Clear selection" else "Select all")
                                }
                                Spacer(Modifier.weight(1f))
                                FilledTonalButton(
                                    enabled = selected.isNotEmpty(),
                                    onClick = { confirmDelete = true }
                                ) {
                                    Text("Delete (${selected.size})")
                                }
                            }
                        }
                    }
                }
            }
            if (files.isEmpty()) {
                item(key = "storage-empty") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 56.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            Icons.Outlined.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                        Text("No tool files stored", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(files, key = { it.name }) { file ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = file.name in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + file.name else selected - file.name
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            MediaFileCard(store, file.name)
                            Text(
                                android.text.format.Formatter.formatFileSize(context, file.length()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp, top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${selected.size} files?") },
            text = { Text("Messages will remain, but these files will no longer be available in chat.") },
            confirmButton = {
                TextButton(onClick = {
                    val names = selected
                    scope.launch { withContext(Dispatchers.IO) { store.delete(names) } }
                    selected = emptySet()
                    confirmDelete = false
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun MediaFileCard(store: ToolFiles, name: String) {
    val revision by ToolFiles.revision.collectAsStateWithLifecycle()
    val file = remember(name, revision) { store.resolve(name) }
    var view by remember(name) { mutableStateOf(false) }
    var bitmap by remember(name) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(name, revision) {
        bitmap = if (file?.mime()?.startsWith("image/") == true) thumbnail(file, 256) else null
    }
    Card(modifier = Modifier.fillMaxWidth().clickable(enabled = file != null) { view = true }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (file == null) {
                Text("File deleted", style = MaterialTheme.typography.bodySmall)
            } else {
                bitmap?.let { Image(it, "Generated image", Modifier.fillMaxWidth().heightIn(max = 160.dp)) }
                Text(
                    if (file.mime().startsWith("video/")) "▶ View video"
                    else if (file.extension == "txt") "View tool details"
                    else "View ${file.extension.uppercase()} file",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (view && file != null) MediaViewer(store, file) { view = false }
}

@Composable
private fun MediaViewer(store:ToolFiles,file:File,onDismiss:()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var bitmap by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var details by remember { mutableStateOf("") }
    var video:VideoView? by remember { mutableStateOf(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(file.mime())) { uri ->
        if(uri!=null) scope.launch {
            val result=withContext(Dispatchers.IO){runCatching{context.contentResolver.openOutputStream(uri)!!.use{out->file.inputStream().use{it.copyTo(out)}}}}
            Toast.makeText(context,if(result.isSuccess) "File saved" else "Could not save file",Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(file.name){
        if(file.mime().startsWith("image/")) bitmap=thumbnail(file,1024)
        if(file.extension=="txt") details=withContext(Dispatchers.IO){runCatching{file.reader().use { r -> val c=CharArray(12000);val n=r.read(c);if(n>0) String(c,0,n) else "" }}.getOrDefault("File deleted")}
    }
    DisposableEffect(Unit){onDispose{video?.stopPlayback()}}
    Dialog(onDismissRequest=onDismiss) {
        Surface(shape=MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().heightIn(max=620.dp).padding(12.dp)) {
                LazyColumn(Modifier.weight(1f,fill=false)) {
                    item {
                        bitmap?.let{Image(it,"Generated image",Modifier.fillMaxWidth().heightIn(max=420.dp))}
                        if(file.mime().startsWith("video/")) AndroidView(factory={ctx->VideoView(ctx).also{v->video=v;v.setVideoPath(file.path);v.setMediaController(MediaController(ctx));v.setOnPreparedListener{v.start()};v.setOnErrorListener{_,_,_->Toast.makeText(ctx,"Unable to play this video",Toast.LENGTH_SHORT).show();true}}},modifier=Modifier.fillMaxWidth().height(320.dp))
                        if(details.isNotEmpty()) Text(details,style=MaterialTheme.typography.bodySmall)
                    }
                }
                Row {
                    TextButton(onClick={save.launch(file.name)}){Text("Save")}
                    TextButton(onClick={
                        runCatching {
                            val uri=FileProvider.getUriForFile(context,"${context.packageName}.tool-files",file)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(file.mime()).putExtra(Intent.EXTRA_STREAM,uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Share file"))
                        }.onFailure{Toast.makeText(context,"Could not share file",Toast.LENGTH_SHORT).show()}
                    }){Text("Share")}
                    TextButton(onClick={confirmDelete=true}){Text("Delete")}
                }
                TextButton(onClick=onDismiss){Text("Close")}
            }
        }
    }
    if(confirmDelete) AlertDialog(onDismissRequest={confirmDelete=false},title={Text("Delete file?")},text={Text("This file will no longer be available in chat.")},
        confirmButton={TextButton(onClick={video?.stopPlayback();scope.launch{withContext(Dispatchers.IO){store.delete(listOf(file.name))};onDismiss()};confirmDelete=false}){Text("Delete")}},
        dismissButton={TextButton(onClick={confirmDelete=false}){Text("Cancel")}})
}
