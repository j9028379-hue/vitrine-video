package com.vitrinevideo.app.player

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Clean ExoPlayer wrapper bound to a TextureView for 9:16 rounded video preview
 * in both Scene Review and Final Film playback. Released explicitly before Media3 Transformer export.
 */
class VitrineMediaPlayer(private val context: Context) {

    interface Listener {
        fun onProgress(positionMs: Long, durationMs: Long, isPlaying: Boolean)
        fun onPlaybackError(message: String)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var currentListener: Listener? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            val exo = player ?: return
            currentListener?.onProgress(
                positionMs = exo.currentPosition.coerceAtLeast(0L),
                durationMs = exo.duration.takeIf { it > 0L } ?: 0L,
                isPlaying = exo.isPlaying
            )
            handler.postDelayed(this, 100L)
        }
    }

    fun playFile(
        file: File,
        textureView: TextureView,
        loop: Boolean,
        autoPlay: Boolean = true,
        listener: Listener? = null
    ) {
        release()
        currentListener = listener

        val exo = ExoPlayer.Builder(context).build().apply {
            repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            volume = 1.0f
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    currentListener?.onProgress(
                        currentPosition.coerceAtLeast(0L),
                        duration.takeIf { it > 0L } ?: 0L,
                        isPlaying
                    )
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    currentListener?.onProgress(
                        currentPosition.coerceAtLeast(0L),
                        duration.takeIf { it > 0L } ?: 0L,
                        isPlaying
                    )
                }

                override fun onPlayerError(error: PlaybackException) {
                    currentListener?.onPlaybackError(error.message ?: "Erreur lecture vidéo")
                }
            })
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
            playWhenReady = autoPlay
        }
        player = exo

        if (textureView.isAvailable) {
            exo.setVideoTextureView(textureView)
        }
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                player?.setVideoTextureView(textureView)
            }

            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                player?.clearVideoTextureView(textureView)
                return true
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
        }

        handler.post(tickRunnable)
    }

    fun replayWithSound() {
        val exo = player ?: return
        exo.volume = 1.0f
        exo.seekTo(0L)
        exo.playWhenReady = true
        exo.play()
    }

    fun togglePlayPause(): Boolean {
        val exo = player ?: return false
        return if (exo.isPlaying) {
            exo.pause()
            false
        } else {
            if (exo.playbackState == Player.STATE_ENDED) {
                exo.seekTo(0L)
            }
            exo.play()
            true
        }
    }

    fun pause() {
        player?.pause()
    }

    fun seekToFraction(fraction: Float) {
        val exo = player ?: return
        val dur = exo.duration
        if (dur > 0L) {
            exo.seekTo((dur * fraction.coerceIn(0f, 1f)).toLong())
        }
    }

    fun release() {
        handler.removeCallbacks(tickRunnable)
        currentListener = null
        player?.release()
        player = null
    }
}
