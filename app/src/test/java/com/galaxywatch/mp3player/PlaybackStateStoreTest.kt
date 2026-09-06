package com.galaxywatch.mp3player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackStateStoreTest {
    @Test
    fun playbackQueueRoundTripsInOrder() {
        val queue = listOf("/music/one.mp3", "/music/album/two.m4a", "/music/three.mp3")

        assertEquals(queue, PlaybackQueueCodec.decode(PlaybackQueueCodec.encode(queue)))
        assertTrue(PlaybackQueueCodec.decode(null).isEmpty())
    }
}
