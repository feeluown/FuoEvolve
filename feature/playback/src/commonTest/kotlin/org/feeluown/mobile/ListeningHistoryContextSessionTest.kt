@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.feeluown.mobile

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ListeningHistoryContextSessionTest {
    @Test
    fun tracksInSamePlaybackContextShareContextSession() = runTest {
        val sink = ContextRecordingSink()
        var wallClock = 10_000L
        var monotonicClock = 0L
        val recorder = ListeningHistoryRecorder(sink, backgroundScope, { wallClock }, { monotonicClock })
        val context = PlaybackContextSnapshot(
            type = PlaybackContextType.Playlist,
            sourceId = "netease",
            resourceId = "playlist-1",
            title = "通勤歌单",
            subtitle = "网易云音乐",
            coverUrl = "https://example.invalid/cover.jpg",
        )
        val firstQueue = PlaybackQueueState(
            listeningContext = context,
            listeningContextSequence = 7L,
            lastPlaybackStartReason = PlaybackStartReason.PLAYLIST_REPLACE,
            playbackStartSequence = 1L,
        )
        val firstTrack = track("track-1")
        val secondTrack = track("track-2")

        recorder.onPlaybackState(PlaybackState(PlayerStatus.Playing, firstTrack, durationMs = 60_000L), firstQueue)
        monotonicClock += 35_000L
        wallClock += 35_000L
        recorder.onPlaybackState(PlaybackState(PlayerStatus.Ended, firstTrack, durationMs = 60_000L), firstQueue)

        val secondQueue = firstQueue.copy(
            lastPlaybackStartReason = PlaybackStartReason.AUTO_NEXT,
            playbackStartSequence = 2L,
        )
        recorder.onPlaybackState(PlaybackState(PlayerStatus.Playing, secondTrack, durationMs = 60_000L), secondQueue)
        monotonicClock += 35_000L
        wallClock += 35_000L
        recorder.onPlaybackState(PlaybackState(PlayerStatus.Ended, secondTrack, durationMs = 60_000L), secondQueue)
        runCurrent()

        val finals = sink.records.filter { it.endedAtMillis != null }
        assertEquals(2, finals.size)
        val contextSessionKey = assertNotNull(finals.first().contextSessionKey)
        assertEquals(contextSessionKey, finals.last().contextSessionKey)
        assertTrue(finals.all { it.qualified })
        val playlistRelation = finals.first().resources.first { it.relation == ListeningResourceRelationType.PlaylistContext }
        assertEquals("netease", playlistRelation.resource.sourceId)
        assertEquals("playlist-1", playlistRelation.resource.sourceResourceId)
        assertEquals("通勤歌单", playlistRelation.resource.title)
        assertEquals("网易云音乐", playlistRelation.resource.subtitle)
        assertEquals("https://example.invalid/cover.jpg", playlistRelation.resource.coverUrl)
    }

    @Test
    fun newPlaybackContextSequenceCreatesAnotherContextSession() = runTest {
        val sink = ContextRecordingSink()
        var wallClock = 20_000L
        var monotonicClock = 0L
        val recorder = ListeningHistoryRecorder(sink, backgroundScope, { wallClock }, { monotonicClock })
        val context = PlaybackContextSnapshot(
            type = PlaybackContextType.Search,
            sourceId = "search",
            resourceId = "jazz",
            title = "jazz",
        )
        val track = track("track-1")
        val first = PlaybackQueueState(
            listeningContext = context,
            listeningContextSequence = 1L,
            lastPlaybackStartReason = PlaybackStartReason.USER_SELECTION,
            playbackStartSequence = 1L,
        )

        recorder.onPlaybackState(PlaybackState(PlayerStatus.Playing, track, durationMs = 60_000L), first)
        monotonicClock += 10_000L
        wallClock += 10_000L
        recorder.onPlaybackState(PlaybackState(PlayerStatus.Ended, track, durationMs = 60_000L), first)

        val second = first.copy(
            listeningContextSequence = 2L,
            playbackStartSequence = 2L,
        )
        recorder.onPlaybackState(PlaybackState(PlayerStatus.Playing, track, durationMs = 60_000L), second)
        monotonicClock += 10_000L
        wallClock += 10_000L
        recorder.onPlaybackState(PlaybackState(PlayerStatus.Ended, track, durationMs = 60_000L), second)
        runCurrent()

        val finals = sink.records.filter { it.endedAtMillis != null }
        assertEquals(2, finals.size)
        assertNotEquals(finals.first().contextSessionKey, finals.last().contextSessionKey)
        assertTrue(finals.first().resources.any { it.relation == ListeningResourceRelationType.SearchContext })
    }

    @Test
    fun upNextTrackDoesNotInheritSourceContextSession() = runTest {
        val sink = ContextRecordingSink()
        val recorder = ListeningHistoryRecorder(sink, backgroundScope, { 30_000L }, { 0L })
        val track = track("track-up-next")
        val queue = PlaybackQueueState(
            currentIsUpNext = true,
            listeningContext = PlaybackContextSnapshot(
                type = PlaybackContextType.Playlist,
                sourceId = "netease",
                resourceId = "playlist-1",
                title = "歌单",
            ),
            listeningContextSequence = 3L,
            playbackStartSequence = 1L,
        )

        recorder.onPlaybackState(PlaybackState(PlayerStatus.Playing, track, durationMs = 60_000L), queue)
        runCurrent()

        val record = sink.records.last()
        assertEquals(null, record.contextSessionKey)
        assertTrue(record.resources.none { it.relation == ListeningResourceRelationType.PlaylistContext })
    }

    private fun track(id: String) = MusicTrack(
        id = id,
        title = id,
        artists = "Artist",
        album = "Album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
        durationMs = 60_000L,
    )
}

private class ContextRecordingSink : ListeningHistorySink {
    val records = mutableListOf<ListeningHistoryRecord>()

    override suspend fun upsert(record: ListeningHistoryRecord) {
        records += record
    }
}
