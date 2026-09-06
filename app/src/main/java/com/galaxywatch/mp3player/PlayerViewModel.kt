package com.galaxywatch.mp3player

import android.app.Application
import android.content.ComponentName
import android.media.AudioManager
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
    val volumeLevel: Int = 0,
    val volumeMax: Int = 0,
    val volumeAdjustable: Boolean = true,
    val sortMode: SortMode = SortMode.TITLE,
    val queueLabel: String = "All songs",
    val playerStarting: Boolean = false,
    val error: String? = null,
)

private data class PendingPlayback(
    val track: Track,
    val queue: List<Track>,
)

class PlayerViewModel(application: Application) : AndroidViewModel(application), Player.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanExecutor = Executors.newSingleThreadExecutor()
    private val libraryRepository = LibraryRepository(application)
    private val preferences = application.getSharedPreferences("player", Application.MODE_PRIVATE)
    private val playbackStateStore = PlaybackStateStore(application)
    private val audioManager = application.getSystemService(AudioManager::class.java)
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var activeQueueIds: List<String> = emptyList()
    private var activeQueueLabel: String = playbackStateStore.load()?.queueLabel ?: "All songs"
    private var pendingPlayback: PendingPlayback? = null

    var state by mutableStateOf(
        PlayerUiState(
            sortMode = SortMode.values().firstOrNull {
                it.name == preferences.getString("sort_mode", null)
            } ?: SortMode.TITLE,
            volumeLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            volumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            volumeAdjustable = !audioManager.isVolumeFixed,
        ),
    )
        private set

    private val progressUpdate = object : Runnable {
        override fun run() {
            updatePlaybackState()
            if (controller?.isPlaying == true) mainHandler.postDelayed(this, 500L)
        }
    }

    private val delayedControllerConnection = Runnable(::connectController)

    init {
        refreshTracks()
        mainHandler.postDelayed(delayedControllerConnection, PLAYER_CONNECTION_DELAY_MS)
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

    fun play(track: Track, queue: List<Track>, queueLabel: String) {
        val playableQueue = queue.distinctBy { it.id }.ifEmpty { listOf(track) }
        activeQueueIds = playableQueue.map { it.id }
        activeQueueLabel = queueLabel
        playbackStateStore.saveQueueLabel(queueLabel)
        state = state.copy(
            currentTrackId = track.id,
            queueLabel = queueLabel,
            playerStarting = controller == null,
            error = null,
        )

        val player = controller
        if (player == null) {
            pendingPlayback = PendingPlayback(track, playableQueue)
            mainHandler.removeCallbacks(delayedControllerConnection)
            connectController()
            return
        }
        startPlayback(player, track, playableQueue)
    }

    private fun startPlayback(player: Player, track: Track, queue: List<Track>) {
        pendingPlayback = null
        val index = queue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        player.setMediaItems(queue.map(::mediaItem), index, 0L)
        player.prepare()
        player.play()
        state = state.copy(playerStarting = false)
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

    fun volumeDown() = adjustMusicVolume(AudioManager.ADJUST_LOWER)

    fun volumeUp() = adjustMusicVolume(AudioManager.ADJUST_RAISE)

    private fun adjustMusicVolume(direction: Int) {
        runCatching {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        }.onFailure { error ->
            state = state.copy(error = error.message ?: "Could not change volume")
        }
        updateVolumeState()
    }

    private fun updateVolumeState() {
        state = state.copy(
            volumeLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            volumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            volumeAdjustable = !audioManager.isVolumeFixed,
        )
    }

    fun cycleSort() {
        val modes = SortMode.values()
        val newMode = modes[(state.sortMode.ordinal + 1) % modes.size]
        val sortedTracks = sortTracks(state.tracks, newMode)
        state = state.copy(sortMode = newMode, tracks = sortedTracks)
        preferences.edit().putString("sort_mode", newMode.name).apply()

        val libraryIds = sortedTracks.map { it.id }
        val queueContainsWholeLibrary = activeQueueIds.size == libraryIds.size &&
            activeQueueIds.toSet() == libraryIds.toSet()
        if (!queueContainsWholeLibrary) return

        controller?.let { player ->
            val currentId = player.currentMediaItem?.mediaId ?: return@let
            val currentIndex = sortedTracks.indexOfFirst { it.id == currentId }
            if (currentIndex < 0) return@let

            val position = player.currentPosition.coerceAtLeast(0L)
            val shouldPlay = player.playWhenReady
            activeQueueIds = libraryIds
            player.setMediaItems(sortedTracks.map(::mediaItem), currentIndex, position)
            player.prepare()
            player.playWhenReady = shouldPlay
        }
    }

    private fun connectController() {
        if (controller != null || controllerFuture != null) return
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
                        if (activeQueueIds.isEmpty()) {
                            activeQueueIds = (0 until mediaController.mediaItemCount)
                                .map { mediaController.getMediaItemAt(it).mediaId }
                        }
                        state = state.copy(connected = true, error = null)
                        val pending = pendingPlayback
                        if (pending != null) {
                            startPlayback(mediaController, pending.track, pending.queue)
                        } else {
                            updatePlaybackState()
                        }
                    }
                    .onFailure { error ->
                        controllerFuture = null
                        pendingPlayback = null
                        state = state.copy(
                            playerStarting = false,
                            error = error.message ?: "Could not connect to playback",
                        )
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
            queueLabel = activeQueueLabel,
            playerStarting = false,
            volumeLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
            volumeMax = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            volumeAdjustable = !audioManager.isVolumeFixed,
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

    private companion object {
        const val PLAYER_CONNECTION_DELAY_MS = 750L
    }
}
