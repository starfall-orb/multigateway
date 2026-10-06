package org.starfall.multigateway.data.service

import android.content.Context
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SpeechAudioPlayer(private val context: Context) {
    private var player: ExoPlayer? = null
    private var currentFile: File? = null

    suspend fun play(audio: ByteArray, onFinished: () -> Unit = {}, onError: () -> Unit = {}) =
        withContext(Dispatchers.Main.immediate) {
            stop()
            var file: File? = null
            var ownedPlayer: ExoPlayer? = null
            try {
                withContext(Dispatchers.IO) {
                    file = File.createTempFile("multigateway_tts_", ".mp3", context.cacheDir)
                    file!!.writeBytes(audio)
                }
                val preparedFile = file!!
                val next = MediaPlayers.create(context, speech = true)
                ownedPlayer = next
                currentFile = preparedFile
                player = next
                fun finish(error: Boolean) {
                    if (player !== next) return
                    stop()
                    if (error) onError()
                    onFinished()
                }
                next.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) finish(false)
                    }
                    override fun onPlayerError(error: PlaybackException) = finish(true)
                })
                next.setMediaItem(MediaPlayers.item(preparedFile.path))
                next.prepare()
                next.play()
            } catch (error: Throwable) {
                // A cancelled file write must not stop a newer speech request.
                if (ownedPlayer != null && player === ownedPlayer) stop()
                file?.delete()
                throw error
            }
        }

    fun stop() {
        val previous = player
        player = null
        previous?.release()
        currentFile?.delete()
        currentFile = null
    }

    fun shutdown() = stop()
}
