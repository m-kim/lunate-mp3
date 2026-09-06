package com.galaxywatch.mp3player

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.File

class PlaybackService : MediaSessionService(), Player.Listener {
    private var mediaSession: MediaSession? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var stateStore: PlaybackStateStore

    private val saveProgress = object : Runnable {
        override fun run() {
            val player = mediaSession?.player ?: return
            stateStore.save(player)
            if (player.isPlaying) mainHandler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        stateStore = PlaybackStateStore(this)
        restorePlayback(player)
        player.addListener(this)
        mediaSession = MediaSession.Builder(this, player).build()
    }

    private fun restorePlayback(player: Player) {
        val saved = stateStore.load() ?: return
        val existingFiles = saved.queueIds.map(::File)
            .filter { it.isFile && SupportedAudio.isSupported(it) }
        if (existingFiles.isEmpty()) return

        val currentIndex = existingFiles.indexOfFirst { it.absolutePath == saved.currentId }
            .takeIf { it >= 0 } ?: 0
        val items = existingFiles.map { file ->
            val isCurrent = file.absolutePath == saved.currentId
            MediaItem.Builder()
                .setMediaId(file.absolutePath)
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(if (isCurrent) saved.title ?: file.nameWithoutExtension else file.nameWithoutExtension)
                        .setArtist(if (isCurrent) saved.artist else null)
                        .setIsPlayable(true)
                        .build(),
                )
                .build()
        }
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeatMode
        player.setMediaItems(items, currentIndex, saved.positionMs)
        player.prepare()
        player.playWhenReady = saved.playWhenReady
    }

    override fun onEvents(player: Player, events: Player.Events) {
        stateStore.save(player)
        mainHandler.removeCallbacks(saveProgress)
        if (player.isPlaying) mainHandler.postDelayed(saveProgress, SAVE_INTERVAL_MS)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        mediaSession?.run {
            stateStore.save(player)
            player.removeListener(this@PlaybackService)
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
    }
}
