package com.galaxywatch.mp3player

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors

data class TransferState(
    val active: Boolean = false,
    val address: String? = null,
    val accessCode: String = "",
    val trackCount: Int = 0,
    val musicBytes: Long? = null,
    val availableForMusicBytes: Long? = null,
    val lastMessage: String? = null,
    val error: String? = null,
)

class UploadViewModel(application: Application) : AndroidViewModel(application) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val countExecutor = Executors.newSingleThreadExecutor()
    private val libraryRepository = LibraryRepository(application)
    private var server: UploadServer? = null

    var state by mutableStateOf(TransferState())
        private set

    init {
        refreshTransferStats()
    }

    fun startTransfer() {
        if (server != null) return
        refreshTransferStats()
        val code = SecureRandom().nextInt(1_000_000).toString().padStart(6, '0')
        state = state.copy(active = true, accessCode = code, error = null, lastMessage = null)

        val uploadServer = UploadServer(
            context = getApplication(),
            accessCode = code,
            onUploadComplete = { file ->
                mainHandler.post {
                    state = state.copy(
                        lastMessage = "Added ${file.name}",
                        error = null,
                    )
                    refreshTransferStats()
                }
            },
            onError = { message -> mainHandler.post { state = state.copy(error = message) } },
        )
        val address = uploadServer.start()
        if (address == null) {
            uploadServer.close()
        } else {
            server = uploadServer
        }
        state = state.copy(
            address = address,
            error = if (address == null) "Connect the watch to Wi-Fi, then try again." else null,
        )
    }

    fun retryTransfer() {
        stopTransfer()
        startTransfer()
    }

    fun stopTransfer() {
        server?.close()
        server = null
        state = TransferState(
            trackCount = state.trackCount,
            musicBytes = state.musicBytes,
            availableForMusicBytes = state.availableForMusicBytes,
        )
    }

    private fun refreshTransferStats() {
        countExecutor.execute {
            val count = runCatching { libraryRepository.cachedTrackCount() }.getOrDefault(0)
            val storage = runCatching {
                StorageStatus.read(File(getApplication<Application>().filesDir, "music"))
            }.getOrNull()
            mainHandler.post {
                state = state.copy(
                    trackCount = count,
                    musicBytes = storage?.musicBytes ?: state.musicBytes,
                    availableForMusicBytes = storage?.availableForMusicBytes
                        ?: state.availableForMusicBytes,
                )
            }
        }
    }

    override fun onCleared() {
        server?.close()
        server = null
        countExecutor.shutdownNow()
        super.onCleared()
    }
}
