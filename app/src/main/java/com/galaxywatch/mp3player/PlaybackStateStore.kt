package com.galaxywatch.mp3player

import android.content.Context
import androidx.media3.common.Player

internal data class SavedPlayback(
    val queueIds: List<String>,
    val currentId: String,
    val positionMs: Long,
    val playWhenReady: Boolean,
    val shuffle: Boolean,
    val repeatMode: Int,
    val title: String?,
    val artist: String?,
    val queueLabel: String,
)

internal class PlaybackStateStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun save(player: Player) {
        val current = player.currentMediaItem ?: return
        val queueIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        if (queueIds.isEmpty()) return

        preferences.edit()
            .putString(KEY_QUEUE, PlaybackQueueCodec.encode(queueIds))
            .putString(KEY_CURRENT_ID, current.mediaId)
            .putLong(KEY_POSITION, player.currentPosition.coerceAtLeast(0L))
            .putBoolean(KEY_PLAY_WHEN_READY, player.playWhenReady)
            .putBoolean(KEY_SHUFFLE, player.shuffleModeEnabled)
            .putInt(KEY_REPEAT, player.repeatMode)
            .putString(KEY_TITLE, current.mediaMetadata.title?.toString())
            .putString(KEY_ARTIST, current.mediaMetadata.artist?.toString())
            .apply()
    }

    fun saveQueueLabel(label: String) {
        preferences.edit().putString(KEY_QUEUE_LABEL, label).apply()
    }

    fun load(): SavedPlayback? {
        val queueIds = PlaybackQueueCodec.decode(preferences.getString(KEY_QUEUE, null))
        val currentId = preferences.getString(KEY_CURRENT_ID, null) ?: return null
        if (queueIds.isEmpty()) return null
        return SavedPlayback(
            queueIds = queueIds,
            currentId = currentId,
            positionMs = preferences.getLong(KEY_POSITION, 0L).coerceAtLeast(0L),
            playWhenReady = preferences.getBoolean(KEY_PLAY_WHEN_READY, false),
            shuffle = preferences.getBoolean(KEY_SHUFFLE, false),
            repeatMode = preferences.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF),
            title = preferences.getString(KEY_TITLE, null),
            artist = preferences.getString(KEY_ARTIST, null),
            queueLabel = preferences.getString(KEY_QUEUE_LABEL, "All songs") ?: "All songs",
        )
    }

    private companion object {
        const val PREFERENCES_NAME = "player"
        const val KEY_QUEUE = "last_queue"
        const val KEY_CURRENT_ID = "last_current_id"
        const val KEY_POSITION = "last_position_ms"
        const val KEY_PLAY_WHEN_READY = "last_play_when_ready"
        const val KEY_SHUFFLE = "last_shuffle"
        const val KEY_REPEAT = "last_repeat"
        const val KEY_TITLE = "last_title"
        const val KEY_ARTIST = "last_artist"
        const val KEY_QUEUE_LABEL = "last_queue_label"
    }
}

internal object PlaybackQueueCodec {
    private const val SEPARATOR = '\u0000'

    fun encode(ids: List<String>): String = ids.joinToString(SEPARATOR.toString())

    fun decode(value: String?): List<String> = value
        ?.split(SEPARATOR)
        ?.filter(String::isNotBlank)
        .orEmpty()
}
