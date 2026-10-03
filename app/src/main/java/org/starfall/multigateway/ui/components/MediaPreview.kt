package org.starfall.multigateway.ui.components

import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.webkit.MimeTypeMap
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.starfall.multigateway.data.service.AttachmentResolver

fun mediaMimeType(name: String): String = MimeTypeMap.getSingleton()
    .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

fun isPreviewableMedia(mime: String) = mime.startsWith("image/") ||
    mime.startsWith("video/") || mime.startsWith("audio/")

/** Decode a bounded image or video thumbnail off the UI thread, for local files and content URIs. */
suspend fun mediaThumbnail(context: Context, reference: String, mime: String, size: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {
        runCatching {
            if (mime.startsWith("video/")) {
                val retriever = MediaMetadataRetriever()
                try {
                    val uri = Uri.parse(reference)
                    if (uri.scheme == "content") retriever.setDataSource(context, uri)
                    else retriever.setDataSource(uri.path ?: reference)
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: size
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: size
                    val ratio = size.toFloat() / maxOf(width, height, size)
                    val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
                    val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
                    val frame = if (android.os.Build.VERSION.SDK_INT >= 27) {
                        retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, targetWidth, targetHeight)
                    } else {
                        retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { original ->
                            android.graphics.Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true).also { scaled ->
                                if (scaled !== original) original.recycle()
                            }
                        }
                    }
                    frame?.asImageBitmap()
                } finally { retriever.release() }
            } else if (mime.startsWith("image/")) {
                val resolver = AttachmentResolver(context)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.open(reference)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (bounds.outWidth / sample > size || bounds.outHeight / sample > size) sample *= 2
                resolver.open(reference)?.use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
                }?.asImageBitmap()
            } else null
        }.getOrNull()
    }

@Composable
fun MediaPreviewDialog(reference: String, name: String, mime: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Box(Modifier.weight(1f, fill = false)) { MediaContent(reference, mime) }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text("Close") }
            }
        }
    }
}

@Composable
fun MediaContent(reference: String, mime: String) {
    // A new source gets its own player and loading/error state.
    key(reference, mime) {
        when {
            mime.startsWith("image/") -> ZoomableMediaImage(reference, mime)
            mime.startsWith("video/") -> VideoPlayback(reference)
            mime.startsWith("audio/") -> AudioPlayback(reference)
            else -> Text("Preview unavailable for this file type.")
        }
    }
}

@Composable
private fun ZoomableMediaImage(reference: String, mime: String) {
    val context = LocalContext.current
    var loaded by remember { mutableStateOf(false) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(reference) {
        bitmap = mediaThumbnail(context, reference, mime, 2048)
        loaded = true
    }
    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 420.dp).clipToBounds(), contentAlignment = Alignment.Center) {
        bitmap?.let { image ->
            Image(image, "Image preview", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)
                    .transformable(rememberTransformableState { zoom, pan, _ ->
                        scale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (scale == 1f) Offset.Zero else offset + pan
                    })
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
        } ?: if (loaded) Text("Unable to open this image. The file may be unavailable or unsupported.")
        else CircularProgressIndicator()
    }
}

@Composable
private fun VideoPlayback(reference: String) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var view by remember { mutableStateOf<VideoView?>(null) }
    var error by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) view?.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); view?.stopPlayback() }
    }
    Box(Modifier.fillMaxWidth().height(280.dp).background(Color.Black), contentAlignment = Alignment.Center) {
        if (!error) {
            AndroidView(
                factory = { context ->
                    VideoView(context).also { video ->
                        view = video
                        video.setMediaController(MediaController(context))
                        video.setAudioFocusRequest(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        video.setOnPreparedListener {
                            ready = true
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) video.start()
                        }
                        video.setOnErrorListener { _, _, _ -> error = true; true }
                        runCatching {
                            val uri = Uri.parse(reference)
                            if (uri.scheme == null) video.setVideoPath(reference) else video.setVideoURI(uri)
                        }.onFailure { error = true }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.stopPlayback() }
            )
            if (!ready) CircularProgressIndicator()
        } else Text("Unable to play this video. The file may be unavailable or unsupported.",
            color = Color.White, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun AudioPlayback(reference: String) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember { MediaPlayer() }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var seekPosition by remember { mutableStateOf<Float?>(null) }
    val attributes = remember { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build() }
    val focus = remember {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change < 0) { runCatching { player.pause() }; playing = false }
            }.build()
    }
    DisposableEffect(player, lifecycle) {
        player.setAudioAttributes(attributes)
        player.setOnPreparedListener { duration = it.duration.coerceAtLeast(0); ready = true }
        player.setOnCompletionListener {
            playing = false
            position = duration
            audioManager.abandonAudioFocusRequest(focus)
        }
        player.setOnErrorListener { _, _, _ ->
            error = true; playing = false; ready = false
            audioManager.abandonAudioFocusRequest(focus)
            true
        }
        runCatching {
            val uri = Uri.parse(reference)
            if (uri.scheme == null) player.setDataSource(reference) else player.setDataSource(context, uri)
            player.prepareAsync()
        }.onFailure { error = true }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                if (ready) runCatching { player.pause() }
                playing = false
                audioManager.abandonAudioFocusRequest(focus)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            audioManager.abandonAudioFocusRequest(focus)
            player.release()
        }
    }
    LaunchedEffect(ready, playing) {
        while (ready && playing) {
            position = runCatching { player.currentPosition }.getOrDefault(position)
            delay(250)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (error) Text("Unable to play this audio. The file may be unavailable or unsupported.", color = MaterialTheme.colorScheme.error)
        else if (!ready) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = {
                    if (playing) {
                        player.pause()
                        playing = false
                        audioManager.abandonAudioFocusRequest(focus)
                    } else if (audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                        if (position >= duration) { player.seekTo(0); position = 0 }
                        player.start()
                        playing = true
                    }
                }) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (playing) "Pause audio" else "Play audio")
                }
                Slider(
                    value = seekPosition ?: position.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { seekPosition = it },
                    onValueChangeFinished = {
                        seekPosition?.toInt()?.let { player.seekTo(it); position = it }
                        seekPosition = null
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    modifier = Modifier.weight(1f)
                )
            }
            Text("${mediaTime(position)} / ${mediaTime(duration)}", style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.align(Alignment.End))
        }
    }
}

private fun mediaTime(ms: Int): String = "%d:%02d".format(ms / 60000, ms / 1000 % 60)
