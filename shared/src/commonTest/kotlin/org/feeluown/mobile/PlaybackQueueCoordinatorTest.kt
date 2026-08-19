package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackQueueCoordinatorTest {
    @Test
    fun nextConsumesUpNextBeforeAdvancingMainQueue() = runTest {
        val first = track("main:1")
        val second = track("main:2")
        val upNext = track("upnext:1")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(first, second)
            mainQueueIndex = 0
            upNextQueue = listOf(upNext)
        }
        var started: StartRequest? = null
        val coordinator = coordinator(
            queue = queue,
            onStart = { track, skipped, part -> started = StartRequest(track, skipped, part) },
        )

        coordinator.next()

        assertEquals(upNext, started?.track)
        assertEquals(0, queue.mainQueueIndex)
        assertTrue(queue.currentIsUpNext)
        assertEquals(upNext, queue.currentUpNextTrack)
        assertTrue(queue.upNextQueue.isEmpty())
    }

    @Test
    fun queueRepeatWrapsFromLastTrackToFirstTrack() = runTest {
        val first = track("main:1")
        val second = track("main:2")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(first, second)
            mainQueueIndex = 1
            repeatMode = RepeatMode.QUEUE
        }
        var started: StartRequest? = null
        val coordinator = coordinator(
            queue = queue,
            onStart = { track, skipped, part -> started = StartRequest(track, skipped, part) },
        )

        coordinator.next()

        assertEquals(first, started?.track)
        assertEquals(0, queue.mainQueueIndex)
        assertFalse(queue.currentIsUpNext)
    }

    @Test
    fun nextAdvancesPlaybackPartBeforeQueueTrack() = runTest {
        val current = track("main:1")
        val next = track("main:2")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(current, next)
            mainQueueIndex = 0
            repeatMode = RepeatMode.OFF
        }
        var started: StartRequest? = null
        val coordinator = coordinator(
            queue = queue,
            parts = listOf(
                PlaybackPart("part:1", "P1"),
                PlaybackPart("part:2", "P2"),
            ),
            currentPartIndex = 0,
            onStart = { track, skipped, part -> started = StartRequest(track, skipped, part) },
        )

        coordinator.next()

        assertEquals(current, started?.track)
        assertEquals(1, started?.partIndex)
        assertEquals(0, queue.mainQueueIndex)
    }

    @Test
    fun singleRepeatWrapsPlaybackPartsWithoutChangingTrack() = runTest {
        val current = track("main:1")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(current)
            mainQueueIndex = 0
            repeatMode = RepeatMode.SINGLE
        }
        var started: StartRequest? = null
        val coordinator = coordinator(
            queue = queue,
            parts = listOf(
                PlaybackPart("part:1", "P1"),
                PlaybackPart("part:2", "P2"),
            ),
            currentPartIndex = 1,
            onStart = { track, skipped, part -> started = StartRequest(track, skipped, part) },
        )

        coordinator.next()

        assertEquals(current, started?.track)
        assertEquals(0, started?.partIndex)
        assertEquals(0, queue.mainQueueIndex)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        queue: PlaybackQueueController,
        parts: List<PlaybackPart> = emptyList(),
        currentPartIndex: Int = -1,
        onStart: (MusicTrack, Int, Int?) -> Unit,
    ): PlaybackQueueCoordinator = PlaybackQueueCoordinator(
        queue = queue,
        scope = this,
        fallbackTrack = { null },
        playbackParts = { parts },
        currentPartIndex = { currentPartIndex },
        startPlayback = onStart,
        stopPlayback = {},
        persistQueue = {},
        updateQueueState = {},
        appendFeatureQueue = { 0 },
        setTrackChangeDirection = {},
        setMessage = {},
    )

    private fun track(id: String): MusicTrack = MusicTrack(
        id = id,
        title = id,
        artists = "Artist",
        album = "Album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
    )

    private data class StartRequest(
        val track: MusicTrack,
        val skippedCount: Int,
        val partIndex: Int?,
    )
}
