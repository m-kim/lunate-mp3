package com.galaxywatch.mp3player

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.Executors

data class PlayerUiState(
    val tracks: List<Track> = emptyList(),
    val loading: Boolean = true,
    val connected: Boolean = false,
    val currentTrackId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val sortMode: SortMode = SortMode.TITLE,
    val error: String? = null,
)

class PlayerViewModel(application: Application) : AndroidViewModel(application), Player.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private val libraryRepository = LibraryRepository(application)
    private val preferences = application.getSharedPreferences("player", Application.MODE_PRIVATE)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    var state by mutableStateOf(
        PlayerUiState(
            sortMode = SortMode.values().firstOrNull {
                it.name == preferences.getString("sort_mode", null)
            } ?: SortMode.TITLE,
        ),
    )
        private set

    private val progressUpdate = object : Runnable {
        override fun run() {
            updatePlaybackState()
            if (controller?.isPlaying == true) mainHandler.postDelayed(this, 500L)
        }
    }

    init {
        connectController()
        refreshTracks()
    }

    fun refreshTracks() {
        state = state.copy(loading = true)
        scanExecutor.execute {
            val cached = runCatching { libraryRepository.cachedTracks() }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) {
                mainHandler.post {
                    state = state.copy(
                        tracks = sortTracks(cached, state.sortMode),
                        loading = false,
                        error = null,
                    )
                }
            }

            val result = runCatching { libraryRepository.reconcile() }
            mainHandler.post {
                state = result.fold(
                    onSuccess = {
                        state.copy(
                            tracks = sortTracks(it, state.sortMode),
                            loading = false,
                            error = null,
                        )
                    },
                    onFailure = { state.copy(loading = false, error = it.message ?: "Could not read music") },
                )
            }
        }
    }

    fun play(track: Track) {
        val player = controller ?: return
        val items = state.tracks.map(::mediaItem)
        val index = state.tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        player.setMediaItems(items, index, 0L)
        player.prepare()
        player.play()
        updatePlaybackState()
    }

    fun togglePlayback() {
        controller?.let { player ->
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.seekToPreviousMediaItem()
    }

    fun seekBy(offsetMs: Long) {
        controller?.let { player ->
            player.seekTo((player.currentPosition + offsetMs).coerceIn(0L, player.duration.coerceAtLeast(0L)))
        }
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        controller?.let { player ->
            player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    fun cycleSort() {
        val modes = SortMode.values()
        val newMode = modes[(state.sortMode.ordinal + 1) % modes.size]
        val sortedTracks = sortTracks(state.tracks, newMode)
        state = state.copy(sortMode = newMode, tracks = sortedTracks)
        preferences.edit().putString("sort_mode", newMode.name).apply()

        controller?.let { player ->
            val currentId = player.currentMediaItem?.mediaId ?: return@let
            val currentIndex = sortedTracks.indexOfFirst { it.id == currentId }
            if (currentIndex < 0) return@let

            val position = player.currentPosition.coerceAtLeast(0L)
            val shouldPlay = player.playWhenReady
            player.setMediaItems(sortedTracks.map(::mediaItem), currentIndex, position)
            player.prepare()
            player.playWhenReady = shouldPlay
        }
    }

    private fun connectController() {
        val application = getApplication<Application>()
        val token = SessionToken(application, ComponentName(application, PlaybackService::class.java))
        val future = MediaController.Builder(application, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { mediaController ->
                        controller = mediaController
                        mediaController.addListener(this)
                        state = state.copy(connected = true, error = null)
                        updatePlaybackState()
                    }
                    .onFailure { error ->
                        state = state.copy(error = error.message ?: "Could not connect to playback")
                    }
            },
            application.mainExecutor,
        )
    }

    private fun mediaItem(track: Track): MediaItem = MediaItem.Builder()
        .setMediaId(track.id)
        .setUri(Uri.fromFile(track.file))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(track.title)
                .setArtist(track.artist)
                .setIsPlayable(true)
                .build(),
        )
        .build()

    override fun onEvents(player: Player, events: Player.Events) {
        updatePlaybackState()
    }

    private fun updatePlaybackState() {
        val player = controller ?: return
        state = state.copy(
            connected = true,
            currentTrackId = player.currentMediaItem?.mediaId,
            isPlaying = player.isPlaying,
            positionMs = player.currentPosition.coerceAtLeast(0L),
            durationMs = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
        mainHandler.removeCallbacks(progressUpdate)
        if (player.isPlaying) mainHandler.postDelayed(progressUpdate, 500L)
    }

    override fun onCleared() {
        mainHandler.removeCallbacksAndMessages(null)
        scanExecutor.shutdownNow()
        controller?.removeListener(this)
        controller = null
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        super.onCleared()
    }
}
