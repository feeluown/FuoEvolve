package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PlaybackQueueSelectionPortTest {
    @Test
    fun playTracksReplacesSourceQueueStartsSelectionAndLeavesUpNextIntact() = runTest {
        val queued = track("upnext")
        val first = track("first")
        val selected = track("selected")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(track("old"))
            mainQueueIndex = 0
            upNextQueue = listOf(queued)
            queueFeature = ProviderFeature(
                id = "netease_radio",
                providerId = "netease",
                providerName = "网易云音乐",
                title = "私人 FM",
                category = ProviderFeatureCategory.Recommend,
                contentType = ProviderContentType.Songs,
                requiresLogin = true,
            )
            isFmQueue = true
            shuffleBeforeFm = false
        }
        var started: MusicTrack? = null
        var updateCount = 0
        var persistCount = 0
        val coordinator = PlaybackQueueCoordinator(
            queue = queue,
            scope = this,
            fallbackTrack = { null },
            playbackParts = { emptyList() },
            currentPartIndex = { -1 },
            startPlayback = { track, _, _ -> started = track },
            stopPlayback = {},
            persistQueue = { persistCount += 1 },
            updateQueueState = { updateCount += 1 },
            appendFeatureQueue = { 0 },
            setTrackChangeDirection = {},
            setMessage = {},
        )

        coordinator.playTracks(listOf(first, selected), 1)

        assertEquals(selected, started)
        assertEquals(listOf(first, selected), queue.mainQueue)
        assertEquals(1, queue.mainQueueIndex)
        assertEquals(listOf(queued), queue.upNextQueue)
        assertFalse(queue.isFmQueue)
        assertNull(queue.queueFeature)
        assertNull(queue.queuePlaylistId)
        assertEquals(1, updateCount)
        assertEquals(1, persistCount)
    }

    @Test
    fun playTracksKeepsSelectedTrackFirstWhenShuffleIsEnabled() = runTest {
        val first = track("first")
        val selected = track("selected")
        val third = track("third")
        val queue = PlaybackQueueController().apply {
            shuffleEnabled = true
        }
        var started: MusicTrack? = null
        val coordinator = PlaybackQueueCoordinator(
            queue = queue,
            scope = this,
            fallbackTrack = { null },
            playbackParts = { emptyList() },
            currentPartIndex = { -1 },
            startPlayback = { track, _, _ -> started = track },
            stopPlayback = {},
            persistQueue = {},
            updateQueueState = {},
            appendFeatureQueue = { 0 },
            setTrackChangeDirection = {},
            setMessage = {},
        )

        coordinator.playTracks(listOf(first, selected, third), 1)

        assertEquals(selected, started)
        assertEquals(selected, queue.mainQueue.first())
        assertEquals(0, queue.mainQueueIndex)
        assertEquals(listOf(first, selected, third), queue.originalMainQueue)
    }

    private fun track(id: String): MusicTrack = MusicTrack(
        id = id,
        title = id,
        artists = "Artist",
        album = "Album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
    )
}
