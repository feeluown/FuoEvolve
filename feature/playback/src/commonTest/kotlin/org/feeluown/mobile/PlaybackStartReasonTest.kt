package org.feeluown.mobile

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PlaybackStartReasonTest {
    @Test
    fun selectingTrackFromNewPlaylistStartsSelectedTrackAsUserSelection() = runTest {
        val pausedTrack = track("track:a")
        val first = track("track:first")
        val selected = track("track:b")
        val last = track("track:last")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(pausedTrack)
            mainQueueIndex = 0
        }
        var startedTrack: MusicTrack? = null
        var transaction: PlaybackTransaction? = null
        val coordinator = coordinator(queue) { track, _, _ ->
            startedTrack = track
            transaction = queue.activePlaybackTransaction()
        }

        coordinator.playPlaylistTracks(
            tracks = listOf(first, selected, last),
            index = 1,
            sourcePlaylistId = "playlist:new",
        )

        assertEquals(selected, startedTrack)
        assertEquals(PlaybackStartReason.USER_SELECTION, transaction?.reason)
        assertEquals(selected.id, transaction?.targetTrackId)
        assertEquals(1L, transaction?.id)
        assertEquals(selected, queue.currentTrack())
        assertEquals("playlist:new", queue.queuePlaylistId)
    }

    @Test
    fun playingAllNewPlaylistStartsFirstTrackAsPlaylistReplacement() = runTest {
        val pausedTrack = track("track:a")
        val first = track("track:b")
        val second = track("track:c")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(pausedTrack)
            mainQueueIndex = 0
        }
        var startedTrack: MusicTrack? = null
        var transaction: PlaybackTransaction? = null
        val coordinator = coordinator(queue) { track, _, _ ->
            startedTrack = track
            transaction = queue.activePlaybackTransaction()
        }

        coordinator.playAllPlaylistTracks(
            tracks = listOf(first, second),
            sourcePlaylistId = "playlist:new",
        )

        assertEquals(first, startedTrack)
        assertEquals(PlaybackStartReason.PLAYLIST_REPLACE, transaction?.reason)
        assertEquals(first.id, transaction?.targetTrackId)
        assertEquals(first, queue.currentTrack())
    }

    @Test
    fun restoredTrackCannotReplaceNewPlaylistBeforeFirstStart() = runTest {
        val restoredTrack = track("track:restored")
        val first = track("track:new-first")
        val second = track("track:new-second")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(restoredTrack)
            mainQueueIndex = 0
        }
        var startedTrack: MusicTrack? = null
        var transaction: PlaybackTransaction? = null
        var playbackStarted = false
        val coordinator = coordinator(
            queue = queue,
            onQueueUpdate = {
                // Reproduce the old Android race: publishing the replacement queue caused runtime
                // observers to republish the restored paused track, which synchronized it into the
                // current queue slot before startPlayback() read that slot.
                if (!playbackStarted) queue.updateCurrentTrack(restoredTrack)
            },
            onStart = { track, _, _ ->
                playbackStarted = true
                startedTrack = track
                transaction = queue.activePlaybackTransaction()
            },
        )

        coordinator.playAllPlaylistTracks(
            tracks = listOf(first, second),
            sourcePlaylistId = "playlist:new",
        )

        assertEquals(first, startedTrack)
        assertEquals(PlaybackStartReason.PLAYLIST_REPLACE, transaction?.reason)
        assertEquals(first.id, transaction?.targetTrackId)
    }

    @Test
    fun startingCurrentTrackUsesResumeReason() = runTest {
        val pausedTrack = track("track:a")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(pausedTrack)
            mainQueueIndex = 0
        }
        var startedTrack: MusicTrack? = null
        var transaction: PlaybackTransaction? = null
        val coordinator = coordinator(queue) { track, _, _ ->
            startedTrack = track
            transaction = queue.activePlaybackTransaction()
        }

        coordinator.startCurrent()

        assertEquals(pausedTrack, startedTrack)
        assertEquals(PlaybackStartReason.RESUME, transaction?.reason)
    }

    @Test
    fun automaticNextUsesAutoNextReasonAndNewTransaction() = runTest {
        val first = track("track:a")
        val second = track("track:b")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(first, second)
            mainQueueIndex = 0
            repeatMode = RepeatMode.OFF
        }
        val firstTransaction = queue.beginPlaybackTransaction(first.id, PlaybackStartReason.USER_SELECTION)
        var startedTrack: MusicTrack? = null
        var nextTransaction: PlaybackTransaction? = null
        val coordinator = coordinator(queue) { track, _, _ ->
            startedTrack = track
            nextTransaction = queue.activePlaybackTransaction()
        }

        coordinator.next()

        assertEquals(second, startedTrack)
        assertEquals(PlaybackStartReason.AUTO_NEXT, nextTransaction?.reason)
        assertEquals(firstTransaction.id + 1L, assertNotNull(nextTransaction).id)
    }

    private fun TestScope.coordinator(
        queue: PlaybackQueueController,
        onQueueUpdate: () -> Unit = {},
        onStart: (MusicTrack, Int, Int?) -> Unit,
    ): PlaybackQueueCoordinator = PlaybackQueueCoordinator(
        queue = queue,
        scope = this,
        fallbackTrack = { null },
        playbackParts = { emptyList() },
        currentPartIndex = { -1 },
        startPlayback = onStart,
        stopPlayback = {},
        persistQueue = {},
        updateQueueState = onQueueUpdate,
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
}
