package org.starfall.multigateway.ui.components

import org.starfall.multigateway.ui.components.AppDialog as Dialog
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.FullscreenExit
import androidx.compose.material.icons.outlined.Close
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.starfall.multigateway.R

import android.content.Context
import android.graphics.Matrix
import android.view.TextureView
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

/** Coil shares downsampling and cached frames across chat, storage and preview screens. */
suspend fun mediaThumbnail(context: Context, reference: String, mime: String, size: Int): ImageBitmap? =
    if (mime.startsWith("image/") || mime.startsWith("video/")) {
        org.starfall.multigateway.data.service.AppImages.decode(context,
            org.starfall.multigateway.data.service.AppImages.source(reference), size)?.asImageBitmap()
    } else null

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
    var error by remember { mutableStateOf(false) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxWidth().windowHeightIn(minFraction = 0.22f, maxFraction = 0.6f).clipToBounds()
        .onSizeChanged { viewportSize = it }
        .transformable(rememberTransformableState { zoom, pan, _ ->
            scale = (scale * zoom).coerceIn(1f, 5f)
            val boundX = viewportSize.width * (scale - 1f) / 2f
            val boundY = viewportSize.height * (scale - 1f) / 2f
            offset = Offset((offset.x + pan.x).coerceIn(-boundX, boundX),
                (offset.y + pan.y).coerceIn(-boundY, boundY))
        }), contentAlignment = Alignment.Center) {
        coil3.compose.AsyncImage(
            model = org.starfall.multigateway.data.service.AppImages.source(reference),
            contentDescription = "Image preview", contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().windowHeightIn(maxFraction = 0.6f)
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            onSuccess = { loaded = true }, onError = { loaded = true; error = true }
        )
        if (error) Text("Unable to open this image. The file may be unavailable or unsupported.")
        else if (!loaded) CircularProgressIndicator()
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
            VideoPlayback(reference, inline = true, initialPosition = resumePosition, autoPlay = resumePlaying,
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
private fun VideoPlayback(reference: String, inline: Boolean = false,
    fullscreen: Boolean = false, initialPosition: Int = 0, autoPlay: Boolean = true,
    onPositionChanged: (Int) -> Unit = {}, onPlayingChanged: (Boolean) -> Unit = {}, onFullscreen: (() -> Unit)? = null) {
    val playback = rememberMediaPlayback(reference, initialPosition, autoPlay, onPositionChanged, onPlayingChanged)
    val player = playback.player
    var textureView by remember { mutableStateOf<TextureView?>(null) }
    val error = playback.error
    val ready = playback.ready
    val playing = playback.playing
    val duration = playback.duration
    val position = playback.position
    var seekPosition by remember { mutableStateOf<Float?>(null) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    fun fitVideo(view: TextureView) {
        val video = player.videoSize
        if (view.width == 0 || view.height == 0 || video.width == 0 || video.height == 0) return
        val width = video.width * video.pixelWidthHeightRatio
        val ratio = minOf(view.width / width, view.height.toFloat() / video.height)
        view.setTransform(Matrix().apply {
            setScale(width * ratio / view.width, video.height * ratio / view.height, view.width / 2f, view.height / 2f)
        })
    }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) { textureView?.let(::fitVideo) }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    fun togglePlayback() = playback.toggle()
    val controls: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = ::togglePlayback) {
                Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "Pause video" else "Play video", tint = Color.White)
            }
            MediaSeekBar(value = seekPosition ?: position.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { seekPosition = it },
                onValueChangeFinished = {
                    seekPosition?.toInt()?.let { playback.seek(it); onPositionChanged(it) }; seekPosition = null
                }, duration = duration.coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f))
            onFullscreen?.let { open ->
                IconButton(onClick = open) {
                    Icon(if (fullscreen) Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,
                        stringResource(if (fullscreen) R.string.video_exit_fullscreen else R.string.video_fullscreen), tint = Color.White)
                }
            }
        }
    }
    // Inline playback must use the already bounded frame, not remeasure itself
    // from the full width: a portrait aspect ratio would push controls below it.
    Column(if (fullscreen || inline) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
    val zoomModifier = if (inline) Modifier else Modifier.transformable(rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        val boundX = viewportSize.width * (scale - 1f) / 2f
        val boundY = viewportSize.height * (scale - 1f) / 2f
        offset = Offset((offset.x + pan.x).coerceIn(-boundX, boundX),
            (offset.y + pan.y).coerceIn(-boundY, boundY))
    })
    Box(Modifier.fillMaxWidth().then(when { fullscreen || inline -> Modifier.weight(1f); else -> Modifier.windowHeight(0.4f) })
        .background(MaterialTheme.colorScheme.scrim).clipToBounds()
        .onSizeChanged { viewportSize = it }
        .then(zoomModifier).then(if (inline || fullscreen) Modifier.clickable(enabled = ready && !error, onClick = ::togglePlayback) else Modifier),
        contentAlignment = Alignment.Center) {
        if (!error) {
            AndroidView(
                factory = { context ->
                    TextureView(context).also { texture ->
                        textureView = texture
                        player.setVideoTextureView(texture)
                        texture.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> fitVideo(view as TextureView) }

                    }
                },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
                onRelease = { texture -> player.clearVideoTextureView(texture); textureView = null }
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
        thumb = {
            // Material3 gives the thumb a minimum track-height slot. Center the
            // small dot in a taller slot rather than leaving it at the top.
            Box(Modifier.width(8.dp).height(24.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(8.dp).background(Color.White, CircleShape))
            }
        },
        track = { state ->
            SliderDefaults.Track(
                sliderState = state,
                modifier = Modifier.fillMaxWidth().height(3.dp),
                colors = SliderDefaults.colors(activeTrackColor = Color.White, inactiveTrackColor = Color.Gray),
                thumbTrackGapSize = 0.dp,
                drawStopIndicator = null
            )
        })
}

@Composable
private fun AudioPlayback(reference: String) {
    val playback = rememberMediaPlayback(reference)
    val ready = playback.ready
    val error = playback.error
    val playing = playback.playing
    val duration = playback.duration
    val position = playback.position
    var seekPosition by remember { mutableStateOf<Float?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (error) Text("Unable to play this audio. The file may be unavailable or unsupported.", color = MaterialTheme.colorScheme.error)
        else if (!ready) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = playback::toggle) {
                    Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (playing) "Pause audio" else "Play audio")
                }
                Slider(
                    value = seekPosition ?: position.toFloat().coerceIn(0f, duration.toFloat()),
                    onValueChange = { seekPosition = it },
                    onValueChangeFinished = {
                        seekPosition?.toInt()?.let { playback.seek(it) }
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
