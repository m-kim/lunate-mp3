package com.galaxywatch.mp3player

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text

private enum class AppScreen { HOME, LIBRARY, NOW_PLAYING }

class MainActivity : ComponentActivity() {
    private val uploadViewModel by viewModels<UploadViewModel>()
    private val playerViewModel by viewModels<PlayerViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var screen by remember { mutableStateOf(AppScreen.HOME) }
                val transferState = uploadViewModel.state
                val playerState = playerViewModel.state

                if (transferState.active) {
                    KeepScreenAwake()
                    BackHandler { finishTransfer() }
                    TransferScreen(
                        state = transferState,
                        onDone = ::finishTransfer,
                        onRetry = uploadViewModel::retryTransfer,
                    )
                } else {
                    BackHandler(enabled = screen != AppScreen.HOME) { screen = AppScreen.HOME }
                    when (screen) {
                        AppScreen.HOME -> HomeScreen(
                            state = playerState,
                            onLibrary = { screen = AppScreen.LIBRARY },
                            onNowPlaying = { screen = AppScreen.NOW_PLAYING },
                            onTransfer = uploadViewModel::startTransfer,
                        )
                        AppScreen.LIBRARY -> LibraryScreen(
                            state = playerState,
                            onSort = playerViewModel::cycleSort,
                            onTrack = { track ->
                                playerViewModel.play(track)
                                screen = AppScreen.NOW_PLAYING
                            },
                            onBack = { screen = AppScreen.HOME },
                        )
                        AppScreen.NOW_PLAYING -> NowPlayingScreen(
                            state = playerState,
                            onToggle = playerViewModel::togglePlayback,
                            onPrevious = playerViewModel::previous,
                            onNext = playerViewModel::next,
                            onBack15 = { playerViewModel.seekBy(-15_000L) },
                            onForward15 = { playerViewModel.seekBy(15_000L) },
                            onShuffle = playerViewModel::toggleShuffle,
                            onRepeat = playerViewModel::cycleRepeat,
                            onBack = { screen = AppScreen.HOME },
                        )
                    }
                }
            }
        }
    }

    private fun finishTransfer() {
        uploadViewModel.stopTransfer()
        playerViewModel.refreshTracks()
    }

    @Composable
    private fun KeepScreenAwake() {
        DisposableEffect(Unit) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
    }
}

private val Background = Color(0xFF071219)
private val Accent = Color(0xFF6EE7FF)
private val Success = Color(0xFF7DF5A5)
private val Error = Color(0xFFFF8E8E)

@Composable
private fun ScreenColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun HomeScreen(
    state: PlayerUiState,
    onLibrary: () -> Unit,
    onNowPlaying: () -> Unit,
    onTransfer: () -> Unit,
) {
    ScreenColumn {
        Box(
            modifier = Modifier.background(Accent.copy(alpha = 0.15f), CircleShape).padding(18.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("MP3", color = Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(9.dp))
        Text("Wrist MP3", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            if (state.tracks.size == 1) "1 track on watch" else "${state.tracks.size} tracks on watch",
            color = Color.LightGray,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = onLibrary, enabled = state.connected, modifier = Modifier.fillMaxWidth()) {
            Text(if (state.loading) "Loading..." else "Music library")
        }
        if (state.currentTrackId != null) {
            Spacer(Modifier.height(6.dp))
            Button(onClick = onNowPlaying, modifier = Modifier.fillMaxWidth()) { Text("Now playing") }
        }
        Spacer(Modifier.height(6.dp))
        Button(onClick = onTransfer, modifier = Modifier.fillMaxWidth()) { Text("Transfer music") }
        state.error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Error, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun LibraryScreen(
    state: PlayerUiState,
    onSort: () -> Unit,
    onTrack: (Track) -> Unit,
    onBack: () -> Unit,
) {
    ScreenColumn {
        Text("Music library", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Button(onClick = onSort, modifier = Modifier.fillMaxWidth()) {
            Text("Sort: ${state.sortMode.label}")
        }
        Spacer(Modifier.height(8.dp))
        when {
            state.loading -> Text("Reading tracks...", color = Color.LightGray)
            state.tracks.isEmpty() -> Text(
                "No music yet. Use Transfer music to add MP3 files.",
                color = Color.LightGray,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
            else -> state.tracks.forEach { track ->
                Button(
                    onClick = { onTrack(track) },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                append(track.artist)
                                if (state.sortMode == SortMode.ALBUM && track.album != "Unknown album") {
                                    append(" • ")
                                    append(track.album)
                                    if (track.trackNumber > 0) append(" • ${track.trackNumber}")
                                }
                            },
                            color = Color.LightGray,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(formatDuration(track.durationMs), fontSize = 10.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun NowPlayingScreen(
    state: PlayerUiState,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onBack15: () -> Unit,
    onForward15: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onBack: () -> Unit,
) {
    val track = state.tracks.firstOrNull { it.id == state.currentTrackId }
    ScreenColumn {
        Text("Now playing", color = Accent, fontSize = 12.sp)
        Spacer(Modifier.height(5.dp))
        Text(
            track?.title ?: "Nothing selected",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(track?.artist.orEmpty(), color = Color.LightGray, fontSize = 11.sp, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Text(
            "${formatDuration(state.positionMs)} / ${formatDuration(state.durationMs)}",
            color = Color.LightGray,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SymbolButton("⏮", "Previous track", onPrevious, track != null, Modifier.weight(1f))
            SymbolButton(
                if (state.isPlaying) "⏸" else "▶",
                if (state.isPlaying) "Pause" else "Play",
                onToggle,
                track != null,
                Modifier.weight(1f),
            )
            SymbolButton("⏭", "Next track", onNext, track != null, Modifier.weight(1f))
        }
        Spacer(Modifier.height(5.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SymbolButton("↶15", "Back 15 seconds", onBack15, track != null, Modifier.weight(1f))
            SymbolButton("↷15", "Forward 15 seconds", onForward15, track != null, Modifier.weight(1f))
        }
        Spacer(Modifier.height(5.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SymbolButton(
                "🔀",
                if (state.shuffle) "Shuffle on" else "Shuffle off",
                onShuffle,
                modifier = Modifier.weight(1f),
            )
            SymbolButton(
                if (state.repeatMode == Player.REPEAT_MODE_ONE) "🔂" else "🔁",
                when (state.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "Repeat one"
                    Player.REPEAT_MODE_ALL -> "Repeat all"
                    else -> "Repeat off"
                },
                onRepeat,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(7.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Back") }
    }
}

@Composable
private fun SymbolButton(
    symbol: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Text(symbol, fontSize = 20.sp, maxLines = 1)
    }
}

@Composable
private fun TransferScreen(state: TransferState, onDone: () -> Unit, onRetry: () -> Unit) {
    ScreenColumn {
        Text("Wi-Fi transfer", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        if (state.address != null) {
            Text("On your computer, open", color = Color.LightGray, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                    .padding(10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(state.address, color = Accent, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(8.dp))
            Text("Access code", color = Color.LightGray, fontSize = 11.sp)
            Text(state.accessCode, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            Text("Keep this screen open", color = Color.LightGray, fontSize = 11.sp)
        } else {
            Text(
                state.error ?: "Starting transfer...",
                color = if (state.error == null) Color.LightGray else Error,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
            )
            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            }
        }
        state.lastMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = Success, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        state.error?.takeIf { state.address != null }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, color = Error, fontSize = 12.sp, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(10.dp))
        Text("${state.trackCount} tracks", color = Color.LightGray, fontSize = 12.sp)
        Spacer(Modifier.height(7.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "--:--"
    val totalSeconds = durationMs / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}
