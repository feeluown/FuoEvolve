package org.feeluown.mobile

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
        var startReason: PlaybackStartReason? = null
        val coordinator = coordinator(queue) { track, _, _ ->
            startedTrack = track
            startReason = queue.consumePlaybackStartReason()
        }

        coordinator.playPlaylistTracks(
            tracks = listOf(first, selected, last),
            index = 1,
            sourcePlaylistId = "playlist:new",
        )

        assertEquals(selected, startedTrack)
        assertEquals(PlaybackStartReason.USER_SELECTION, startReason)
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
        var startReason: PlaybackStartReason? = null
        val coordinator = coordinator(queue) { track, _, _ ->
            startedTrack = track
            startReason = queue.consumePlaybackStartReason()
        }

        coordinator.playAllPlaylistTracks(
            tracks = listOf(first, second),
            sourcePlaylistId = "playlist:new",
        )

        assertEquals(first, startedTrack)
        assertEquals(PlaybackStartReason.PLAYLIST_REPLACE, startReason)
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
        var startReason: PlaybackStartReason? = null
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
                startReason = queue.consumePlaybackStartReason()
            },
        )

        coordinator.playAllPlaylistTracks(
            tracks = listOf(first, second),
            sourcePlaylistId = "playlist:new",
        )

        assertEquals(first, startedTrack)
        assertEquals(PlaybackStartReason.PLAYLIST_REPLACE, startReason)
    }

    @Test
    fun startingCurrentTrackUsesResumeReason() = runTest {
        val pausedTrack = track("track:a")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(pausedTrack)
            mainQueueIndex = 0
        }
        var startedTrack: MusicTrack? = null
        var startReason: PlaybackStartReason? = null
        val coordinator = coordinator(queue) { track, _, _ ->
            startedTrack = track
            startReason = queue.consumePlaybackStartReason()
        }

        coordinator.startCurrent()

        assertEquals(pausedTrack, startedTrack)
        assertEquals(PlaybackStartReason.RESUME, startReason)
    }

    @Test
    fun automaticNextUsesAutoNextReason() = runTest {
        val first = track("track:a")
        val second = track("track:b")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(first, second)
            mainQueueIndex = 0
            repeatMode = RepeatMode.OFF
        }
        var startedTrack: MusicTrack? = null
        var startReason: PlaybackStartReason? = null
        val coordinator = coordinator(queue) { track, _, _ ->
            startedTrack = track
            startReason = queue.consumePlaybackStartReason()
        }

        coordinator.next()

        assertEquals(second, startedTrack)
        assertEquals(PlaybackStartReason.AUTO_NEXT, startReason)
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
