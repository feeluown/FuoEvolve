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
    fun staticFeatureQueueWrapsWithoutDynamicAppend() = runTest {
        val first = track("main:1")
        val second = track("main:2")
        val queue = PlaybackQueueController().apply {
            repeatMode = RepeatMode.QUEUE
        }
        var started: MusicTrack? = null
        var appendCount = 0
        val coordinator = coordinator(
            queue = queue,
            onStart = { track, _, _ -> started = track },
            appendFeatureQueue = {
                appendCount += 1
                0
            },
        )
        val feature = ProviderFeature(
            id = "netease_recommended_new_songs",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "新歌推荐",
            category = ProviderFeatureCategory.Recommend,
            contentType = ProviderContentType.Songs,
            requiresLogin = false,
        )

        coordinator.playFeatureTracks(listOf(first, second), index = 1, sourceFeature = feature)
        started = null
        coordinator.next()

        assertEquals(first, started)
        assertEquals(0, appendCount)
        assertEquals(null, queue.queueFeature)
        assertFalse(queue.isFmQueue)
    }

    @Test
    fun appendedPlaylistTracksAreMixedIntoPendingShuffleSuffix() = runTest {
        val first = track("main:1")
        val second = track("main:2")
        val third = track("main:3")
        val fourth = track("main:4")
        val fifth = track("main:5")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(first, second, third)
            originalMainQueue = listOf(first, second, third)
            mainQueueIndex = 0
            queuePlaylistId = "playlist:1"
            shuffleEnabled = true
        }
        val coordinator = coordinator(
            queue = queue,
            onStart = { _, _, _ -> },
            shuffleTracks = { it.reversed() },
        )

        coordinator.appendPlaylistTracks("playlist:1", listOf(fourth, fifth))

        assertEquals(listOf(first, fifth, fourth, third, second), queue.mainQueue)
        assertEquals(listOf(first, second, third, fourth, fifth), queue.originalMainQueue)
        assertEquals(0, queue.mainQueueIndex)
    }

    @Test
    fun playAllPlaylistShufflesTheWholeQueueInsteadOfPinningTheFirstTrack() = runTest {
        val first = track("main:1")
        val second = track("main:2")
        val third = track("main:3")
        val tracks = listOf(first, second, third)
        val queue = PlaybackQueueController().apply {
            shuffleEnabled = true
        }
        var started: MusicTrack? = null
        val coordinator = coordinator(
            queue = queue,
            onStart = { track, _, _ -> started = track },
            shuffleTracks = { it },
        )

        coordinator.playAllPlaylistTracks(tracks, "playlist:1")

        assertEquals(second, started)
        assertEquals(listOf(second, third, first), queue.mainQueue)
        assertEquals(tracks, queue.originalMainQueue)
        assertEquals(0, queue.mainQueueIndex)
        assertEquals("playlist:1", queue.queuePlaylistId)
    }

    @Test
    fun selectingPlaylistTrackStillPinsTheSelectedTrackWhenShuffleIsEnabled() = runTest {
        val first = track("main:1")
        val second = track("main:2")
        val third = track("main:3")
        val tracks = listOf(first, second, third)
        val queue = PlaybackQueueController().apply {
            shuffleEnabled = true
        }
        var started: MusicTrack? = null
        val coordinator = coordinator(
            queue = queue,
            onStart = { track, _, _ -> started = track },
            shuffleTracks = { it },
        )

        coordinator.playPlaylistTracks(tracks, index = 1, sourcePlaylistId = "playlist:1")

        assertEquals(second, started)
        assertEquals(second, queue.mainQueue.first())
        assertEquals(tracks, queue.originalMainQueue)
        assertEquals(0, queue.mainQueueIndex)
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

    @Test
    fun explicitMainSelectionResetsDirectionAfterPrevious() = runTest {
        val first = track("main:1")
        val second = track("main:2")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(first, second)
            mainQueueIndex = 1
            repeatMode = RepeatMode.OFF
        }
        val coordinator = coordinator(queue = queue, onStart = { _, _, _ -> })

        coordinator.previous()
        assertEquals(TrackChangeDirection.Previous, coordinator.trackChangeDirection)

        coordinator.playMainIndex(1)
        assertEquals(TrackChangeDirection.Next, coordinator.trackChangeDirection)
    }

    @Test
    fun queueUiPortAddsUpNextAndPublishesQueueMutation() = runTest {
        val current = track("main:1")
        val queued = track("upnext:1")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(current)
            mainQueueIndex = 0
        }
        var updateCount = 0
        var persistCount = 0
        var message = ""
        val coordinator = coordinator(
            queue = queue,
            onStart = { _, _, _ -> },
            updateQueueState = { updateCount += 1 },
            persistQueue = { persistCount += 1 },
            setMessage = { message = it },
        )

        coordinator.addToUpNext(queued)

        assertEquals(listOf(current, queued), coordinator.queue)
        assertEquals(1, coordinator.displayUpNextCount)
        assertEquals(1, updateCount)
        assertEquals(1, persistCount)
        assertEquals("已加入接下来播放：${queued.title}", message)
    }

    @Test
    fun queueUiPortClearKeepsCurrentTrackAndResetsModes() = runTest {
        val current = track("main:1")
        val next = track("main:2")
        val queued = track("upnext:1")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(current, next)
            originalMainQueue = mainQueue
            mainQueueIndex = 0
            upNextQueue = listOf(queued)
            shuffleEnabled = true
            isFmQueue = true
            shuffleBeforeFm = true
        }
        val coordinator = coordinator(
            queue = queue,
            onStart = { _, _, _ -> },
        )

        coordinator.clearQueue()

        assertEquals(listOf(current), queue.mainQueue)
        assertEquals(0, queue.mainQueueIndex)
        assertTrue(queue.upNextQueue.isEmpty())
        assertFalse(queue.isFmQueue)
        assertEquals(null, queue.shuffleBeforeFm)
        assertEquals(listOf(current), coordinator.queue)
    }

    @Test
    fun queueUiPortRepeatCyclesWithoutController() = runTest {
        val queue = PlaybackQueueController().apply {
            repeatMode = RepeatMode.OFF
        }
        val coordinator = coordinator(queue = queue, onStart = { _, _, _ -> })

        coordinator.toggleRepeat()
        assertEquals(RepeatMode.QUEUE, coordinator.repeatMode)
        coordinator.toggleRepeat()
        assertEquals(RepeatMode.SINGLE, coordinator.repeatMode)
        coordinator.toggleRepeat()
        assertEquals(RepeatMode.OFF, coordinator.repeatMode)
    }

    @Test
    fun queueUiPortExposesRestoredAndUpdatedCurrentTrack() = runTest {
        val first = track("main:1")
        val restored = track("main:2").copy(title = "Restored title")
        val queue = PlaybackQueueController().apply {
            restore(
                PlaybackQueueSnapshot(
                    mainQueue = listOf(first, restored),
                    originalMainQueue = emptyList(),
                    upNextQueue = emptyList(),
                    queueIndex = 1,
                    shuffleEnabled = false,
                    repeatMode = RepeatMode.QUEUE,
                    isFmQueue = false,
                    shuffleBeforeFm = null,
                )
            )
        }
        val coordinator = coordinator(queue = queue, onStart = { _, _, _ -> })

        assertEquals(restored, coordinator.currentQueueTrack)

        val edited = restored.copy(title = "Edited local title")
        queue.updateCurrentTrack(edited)

        assertEquals(edited, coordinator.currentQueueTrack)
    }

    private fun kotlinx.coroutines.test.TestScope.coordinator(
        queue: PlaybackQueueController,
        parts: List<PlaybackPart> = emptyList(),
        currentPartIndex: Int = -1,
        onStart: (MusicTrack, Int, Int?) -> Unit,
        persistQueue: () -> Unit = {},
        updateQueueState: () -> Unit = {},
        setMessage: (String) -> Unit = {},
        appendFeatureQueue: suspend (ProviderFeature) -> Int = { 0 },
        shuffleTracks: (List<MusicTrack>) -> List<MusicTrack> = { it.shuffled() },
    ): PlaybackQueueCoordinator = PlaybackQueueCoordinator(
        queue = queue,
        scope = this,
        fallbackTrack = { null },
        playbackParts = { parts },
        currentPartIndex = { currentPartIndex },
        startPlayback = onStart,
        stopPlayback = {},
        persistQueue = persistQueue,
        updateQueueState = updateQueueState,
        appendFeatureQueue = appendFeatureQueue,
        setTrackChangeDirection = {},
        setMessage = setMessage,
        shuffleTracks = shuffleTracks,
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