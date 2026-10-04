package org.starfall.multigateway.ui.components
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.starfall.multigateway.R

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
                MediaViewerToolbar(onClose = onDismiss)
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
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(reference) {
        bitmap = mediaThumbnail(context, reference, mime, 2048)
        loaded = true
    }
    Box(Modifier.fillMaxWidth().windowHeightIn(minFraction = 0.22f, maxFraction = 0.6f).clipToBounds()
        .onSizeChanged { viewportSize = it }
        .transformable(rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            val boundX = viewportSize.width * (scale - 1f) / 2f
            val boundY = viewportSize.height * (scale - 1f) / 2f
            offset = Offset((offset.x + pan.x).coerceIn(-boundX, boundX),
                (offset.y + pan.y).coerceIn(-boundY, boundY))
        }), contentAlignment = Alignment.Center) {
        bitmap?.let { image ->
            Image(image, "Image preview", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().windowHeightIn(maxFraction = 0.6f)
                    .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
        } ?: if (loaded) Text("Unable to open this image. The file may be unavailable or unsupported.")
        else CircularProgressIndicator()
    }
}

@Composable
fun InlineVideoPreview(reference: String, thumbnail: ImageBitmap?, modifier: Modifier = Modifier,
    overlay: @Composable BoxScope.() -> Unit = {}) {
    var activated by remember(reference) { mutableStateOf(false) }
    var fullscreen by remember(reference) { mutableStateOf(false) }
    var resumePosition by remember(reference) { mutableIntStateOf(0) }
    var resumePlaying by remember(reference) { mutableStateOf(true) }
    val ratio = thumbnail?.let { it.width.toFloat() / it.height.coerceAtLeast(1) } ?: (16f / 9f)
    BoundedMediaFrame(ratio, modifier) {
        if (activated && !fullscreen) key(reference) {
            VideoPlayback(reference, inline = true, aspectRatio = ratio, initialPosition = resumePosition, autoPlay = resumePlaying,
                onPositionChanged = { resumePosition = it }, onPlayingChanged = { resumePlaying = it }, onFullscreen = { fullscreen = true })
        }
        else Box(Modifier.fillMaxSize().clickable(onClickLabel = "Play video") { activated = true },
            contentAlignment = Alignment.Center) {
            thumbnail?.let { Image(it, "Video preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            Surface(shape = MaterialTheme.shapes.extraLarge, color = Color.Black.copy(alpha = 0.55f)) {
                Icon(Icons.Default.PlayArrow, "Play video", Modifier.padding(12.dp), tint = Color.White)
            }
            IconButton(onClick = { activated = true; fullscreen = true }, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)) {
                Icon(Icons.Outlined.Fullscreen, stringResource(R.string.video_fullscreen), tint = Color.White)
            }
        }
        overlay()
    }
    if (fullscreen) Dialog(onDismissRequest = { fullscreen = false },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.let { WindowCompat.getInsetsController(it, it.decorView).hide(WindowInsetsCompat.Type.systemBars()) }
            onDispose { }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            VideoPlayback(reference, fullscreen = true, initialPosition = resumePosition, autoPlay = resumePlaying,
                onPositionChanged = { resumePosition = it }, onPlayingChanged = { resumePlaying = it }, onFullscreen = { fullscreen = false })
            IconButton(onClick = { fullscreen = false }, modifier = Modifier.align(Alignment.TopStart).padding(8.dp)) {
                Icon(Icons.Outlined.Close, stringResource(R.string.video_exit_fullscreen), tint = Color.White)
            }
        }
    }
}

@Composable
private fun VideoPlayback(reference: String, inline: Boolean = false, aspectRatio: Float = 16f / 9f,
    fullscreen: Boolean = false, initialPosition: Int = 0, autoPlay: Boolean = true,
    onPositionChanged: (Int) -> Unit = {}, onPlayingChanged: (Boolean) -> Unit = {}, onFullscreen: (() -> Unit)? = null) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(reference) { MediaPlayer() }
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    var error by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(0) }
    var position by remember { mutableIntStateOf(0) }
    var seekPosition by remember { mutableStateOf<Float?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    fun fitVideo(view: TextureView) {
        if (view.width == 0 || view.height == 0 || player.videoWidth == 0 || player.videoHeight == 0) return
        val ratio = minOf(view.width.toFloat() / player.videoWidth, view.height.toFloat() / player.videoHeight)
        view.setTransform(Matrix().apply {
            setScale(player.videoWidth * ratio / view.width, player.videoHeight * ratio / view.height,
                view.width / 2f, view.height / 2f)
        })
    }
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val attributes = remember { AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build() }
    val focus = remember {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change < 0 && ready) { runCatching { player.pause() }; playing = false; onPlayingChanged(false) }
            }.build()
    }
    DisposableEffect(player, lifecycle) {
        player.setAudioAttributes(attributes)
        player.setOnPreparedListener {
            ready = true
            duration = it.duration.coerceAtLeast(0)
            position = initialPosition.coerceIn(0, duration)
            if (position > 0) it.seekTo(position)
            textureView?.let(::fitVideo)
            if (autoPlay && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                it.start()
                playing = true
                onPlayingChanged(true)
            }
        }
        player.setOnVideoSizeChangedListener { _, _, _ -> textureView?.let(::fitVideo) }
        player.setOnCompletionListener {
            playing = false; position = duration
            onPositionChanged(position); onPlayingChanged(false)
            audioManager.abandonAudioFocusRequest(focus)
        }
        player.setOnErrorListener { _, _, _ ->
            error = true; playing = false
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
                onPlayingChanged(false)
                audioManager.abandonAudioFocusRequest(focus)
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            audioManager.abandonAudioFocusRequest(focus)
            if (ready && !error) onPositionChanged(runCatching { player.currentPosition }.getOrDefault(position))
            player.release()
        }
    }
    LaunchedEffect(ready, playing) {
        while (ready && playing) {
            position = runCatching { player.currentPosition }.getOrDefault(position)
            onPositionChanged(position)
            delay(250)
        }
    }
    fun togglePlayback() {
        if (!ready || error) return
        if (playing) {
            player.pause(); playing = false; audioManager.abandonAudioFocusRequest(focus)
        } else if (audioManager.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            if (position >= duration) { player.seekTo(0); position = 0 }
            player.start(); playing = true
        }
        onPositionChanged(runCatching { player.currentPosition }.getOrDefault(position))
        onPlayingChanged(playing)
    }
    val controls: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::togglePlayback) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "Pause video" else "Play video", tint = Color.White)
            }
            MediaSeekBar(value = seekPosition ?: position.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { seekPosition = it },
                onValueChangeFinished = {
                    seekPosition?.toInt()?.let { player.seekTo(it); position = it; onPositionChanged(it) }; seekPosition = null
                }, duration = duration.coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f))
            onFullscreen?.let { open ->
                IconButton(onClick = open) {
                    Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                        stringResource(if (fullscreen) R.string.video_exit_fullscreen else R.string.video_fullscreen), tint = Color.White)
                }
            }
        }
    }
    Column(if (fullscreen) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
    Box(Modifier.fillMaxWidth().then(when { fullscreen -> Modifier.weight(1f); inline -> Modifier.aspectRatio(aspectRatio); else -> Modifier.windowHeight(0.4f) })
        .background(MaterialTheme.colorScheme.scrim).clipToBounds()
        .onSizeChanged { viewportSize = it }
        .transformable(rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            val boundX = viewportSize.width * (scale - 1f) / 2f
            val boundY = viewportSize.height * (scale - 1f) / 2f
            offset = Offset((offset.x + pan.x).coerceIn(-boundX, boundX),
                (offset.y + pan.y).coerceIn(-boundY, boundY))
        }).then(if (inline || fullscreen) Modifier.clickable(enabled = ready && !error, onClick = ::togglePlayback) else Modifier),
        contentAlignment = Alignment.Center) {
        if (!error) {
            AndroidView(
                factory = { context ->
                    TextureView(context).also { texture ->
                        textureView = texture
                        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                Surface(surface).let { output ->
                                    try { player.setSurface(output) } finally { output.release() }
                                }
                                if (ready) fitVideo(texture)
                            }
                            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                                if (ready) fitVideo(texture)
                            }
                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                runCatching { player.setSurface(null) }
                                return true
                            }
                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                        }
                    }
                },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
                onRelease = { textureView = null }
            )
            if (!ready) CircularProgressIndicator()
        } else Text("Unable to play this video. The file may be unavailable or unsupported.",
            color = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.padding(16.dp))
        if ((inline || fullscreen) && ready && !error) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.45f))) { controls() }
        }
    }
    if (!inline && !fullscreen && ready && !error) Box(Modifier.background(Color.Black)) { controls() }
    }
}

@Composable
fun InlineAudioPreview(reference: String) {
    key(reference) { AudioPlayback(reference) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MediaSeekBar(value: Float, onValueChange: (Float) -> Unit, onValueChangeFinished: () -> Unit,
    duration: Float, modifier: Modifier = Modifier) {
    Slider(value = value, onValueChange = onValueChange, onValueChangeFinished = onValueChangeFinished,
        valueRange = 0f..duration, modifier = modifier,
        colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.Gray),
        thumb = { Box(Modifier.size(8.dp).background(Color.White, CircleShape)) },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                val end = Offset(size.width, size.height / 2)
                val start = Offset(0f, size.height / 2)
                drawLine(Color.Gray, start, end, 3.dp.toPx(), StrokeCap.Round)
                drawLine(Color.White, start, Offset(size.width * (state.value / duration).coerceIn(0f, 1f), end.y), 3.dp.toPx(), StrokeCap.Round)
            }
        })
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
