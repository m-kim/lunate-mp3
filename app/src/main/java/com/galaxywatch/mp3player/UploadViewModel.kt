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
        refreshTrackCount()
    }

    fun startTransfer() {
        if (server != null) return
        refreshTrackCount()
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
                    refreshTrackCount()
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
        state = TransferState(trackCount = state.trackCount)
    }

    private fun refreshTrackCount() {
        countExecutor.execute {
            val count = runCatching { libraryRepository.cachedTrackCount() }.getOrDefault(0)
            mainHandler.post { state = state.copy(trackCount = count) }
        }
    }

    override fun onCleared() {
        server?.close()
        server = null
        countExecutor.shutdownNow()
        super.onCleared()
    }
}
