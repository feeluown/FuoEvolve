package org.feeluown.mobile

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ListeningHistoryRecorderTest {
    @Test
    fun recordsOnlyActualPlayingTimeAcrossPauseAndResume() = runTest {
        val sink = RecordingListeningHistorySink()
        var wallClock = 1_000_000L
        var monotonicClock = 0L
        val recorder = ListeningHistoryRecorder(
            sink = sink,
            scope = backgroundScope,
            nowMillis = { wallClock },
            monotonicMillis = { monotonicClock },
        )
        val track = track(durationMs = 60_000L)
        val queue = PlaybackQueueState(
            lastPlaybackStartReason = PlaybackStartReason.USER_SELECTION,
            playbackStartSequence = 1L,
        )

        recorder.onPlaybackState(PlaybackState(PlayerStatus.Playing, track, durationMs = 60_000L), queue)
        monotonicClock += 10_000L
        wallClock += 10_000L
        recorder.onPlaybackState(PlaybackState(PlayerStatus.Paused, track, durationMs = 60_000L), queue)

        monotonicClock += 20_000L
        wallClock += 20_000L
        recorder.onPlaybackState(
            PlaybackState(PlayerStatus.Playing, track, durationMs = 60_000L),
            queue.copy(
                lastPlaybackStartReason = PlaybackStartReason.RESUME,
                playbackStartSequence = 2L,
            ),
        )
        monotonicClock += 25_000L
        wallClock += 25_000L
        recorder.onPlaybackState(
            PlaybackState(PlayerStatus.Ended, track, durationMs = 60_000L),
            queue.copy(
                lastPlaybackStartReason = PlaybackStartReason.RESUME,
                playbackStartSequence = 2L,
            ),
        )
        runCurrent()

        val finalRecord = sink.records.last()
        assertEquals(35_000L, finalRecord.playedMs)
        assertEquals(ListeningCompletionReason.Ended, finalRecord.completionReason)
        assertEquals(ListeningStartReason.UserSelection, finalRecord.startReason)
        assertTrue(finalRecord.qualified)
    }

    @Test
    fun loadingTimeIsNotCountedAsPlayingTime() = runTest {
        val sink = RecordingListeningHistorySink()
        var wallClock = 1_500_000L
        var monotonicClock = 0L
        val recorder = ListeningHistoryRecorder(sink, backgroundScope, { wallClock }, { monotonicClock })
        val track = track(durationMs = 120_000L)
        val queue = PlaybackQueueState(
            lastPlaybackStartReason = PlaybackStartReason.USER_SELECTION,
            playbackStartSequence = 1L,
        )

        recorder.onPlaybackState(PlaybackState(PlayerStatus.Playing, track, durationMs = 120_000L), queue)
        monotonicClock += 10_000L
        wallClock += 10_000L
        recorder.onPlaybackState(PlaybackState(PlayerStatus.Loading, track, durationMs = 120_000L), queue)
        monotonicClock += 20_000L
        wallClock += 20_000L
        recorder.onPlaybackState(PlaybackState(PlayerStatus.Playing, track, durationMs = 120_000L), queue)
        monotonicClock += 5_000L
        wallClock += 5_000L
        recorder.onPlaybackState(PlaybackState(PlayerStatus.Ended, track, durationMs = 120_000L), queue)
        runCurrent()

        assertEquals(15_000L, sink.records.last().playedMs)
    }

    @Test
    fun replayingSameTrackStartsAnotherListeningSession() = runTest {
        val sink = RecordingListeningHistorySink()
        var wallClock = 1_750_000L
        var monotonicClock = 0L
        val recorder = ListeningHistoryRecorder(sink, backgroundScope, { wallClock }, { monotonicClock })
        val track = track(durationMs = 60_000L)
        val firstQueue = PlaybackQueueState(
            lastPlaybackStartReason = PlaybackStartReason.USER_SELECTION,
            playbackStartSequence = 1L,
        )

        recorder.onPlaybackState(PlaybackState(PlayerStatus.Playing, track, durationMs = 60_000L), firstQueue)
        monotonicClock += 20_000L
        wallClock += 20_000L
        recorder.onPlaybackState(
            PlaybackState(PlayerStatus.Loading, track, durationMs = 60_000L),
            firstQueue.copy(
                lastPlaybackStartReason = PlaybackStartReason.AUTO_NEXT,
                playbackStartSequence = 2L,
            ),
        )
        recorder.onPlaybackState(
            PlaybackState(PlayerStatus.Playing, track, durationMs = 60_000L),
            firstQueue.copy(
                lastPlaybackStartReason = PlaybackStartReason.AUTO_NEXT,
                playbackStartSequence = 2L,
            ),
        )
        monotonicClock += 30_000L
        wallClock += 30_000L
        recorder.onPlaybackState(
            PlaybackState(PlayerStatus.Ended, track, durationMs = 60_000L),
            firstQueue.copy(
                lastPlaybackStartReason = PlaybackStartReason.AUTO_NEXT,
                playbackStartSequence = 2L,
            ),
        )
        runCurrent()

        val sessions = sink.records.groupBy { it.sessionKey }
        assertEquals(2, sessions.size)
        val finalRecords = sessions.values.mapNotNull { records -> records.lastOrNull { it.endedAtMillis != null } }
        assertEquals(2, finalRecords.size)
        assertEquals(20_000L, finalRecords.first().playedMs)
        assertEquals(30_000L, finalRecords.last().playedMs)
    }

    @Test
    fun shortAccidentalPlaybackIsPersistedButNotQualified() = runTest {
        val sink = RecordingListeningHistorySink()
        var wallClock = 2_000_000L
        var monotonicClock = 0L
        val recorder = ListeningHistoryRecorder(sink, backgroundScope, { wallClock }, { monotonicClock })
        val track = track(durationMs = 180_000L)

        recorder.onPlaybackState(PlaybackState(PlayerStatus.Playing, track, durationMs = 180_000L), PlaybackQueueState())
        monotonicClock += 4_000L
        wallClock += 4_000L
        recorder.onPlaybackState(PlaybackState(PlayerStatus.Idle, track), PlaybackQueueState())
        runCurrent()

        assertTrue(sink.records.isNotEmpty())
        val finalRecord = sink.records.last()
        assertEquals(4_000L, finalRecord.playedMs)
        assertFalse(finalRecord.qualified)
        assertEquals(ListeningCompletionReason.Stopped, finalRecord.completionReason)
    }

    @Test
    fun smartReplacementKeepsLogicalTrackAndStoresResolvedSource() = runTest {
        val sink = RecordingListeningHistorySink()
        var wallClock = 3_000_000L
        var monotonicClock = 0L
        val recorder = ListeningHistoryRecorder(sink, backgroundScope, { wallClock }, { monotonicClock })
        val track = track(durationMs = 180_000L).copy(
            id = "replacement-runtime-id",
            source = "bilibili",
            isSmartReplacement = true,
            originalId = "netease-song-1",
            originalTitle = "Original Song",
            originalArtists = "Original Artist",
            originalAlbum = "Original Album",
            originalSource = "netease",
            replacementId = "BV1-replacement",
            replacementTitle = "Replacement Video",
            replacementArtists = "Uploader",
            replacementSource = "bilibili",
        )

        recorder.onPlaybackState(PlaybackState(PlayerStatus.Playing, track, durationMs = 180_000L), PlaybackQueueState())
        monotonicClock += 90_000L
        wallClock += 90_000L
        recorder.onPlaybackState(PlaybackState(PlayerStatus.Ended, track, durationMs = 180_000L), PlaybackQueueState())
        runCurrent()

        val record = sink.records.last()
        val primary = record.resources.first { it.relation == ListeningResourceRelationType.Primary }.resource
        val resolved = record.resources.firstOrNull { it.relation == ListeningResourceRelationType.ResolvedSource }?.resource
        assertEquals("netease", primary.sourceId)
        assertEquals("netease-song-1", primary.sourceResourceId)
        assertEquals("Original Song", primary.title)
        assertNotNull(resolved)
        assertEquals("bilibili", resolved.sourceId)
        assertEquals("BV1-replacement", resolved.sourceResourceId)
    }

    @Test
    fun capturesArtistAlbumAndPlaylistDimensions() = runTest {
        val sink = RecordingListeningHistorySink()
        val recorder = ListeningHistoryRecorder(sink, backgroundScope, { 4_000_000L }, { 0L })
        val artist = MediaRef(
            id = "artist-1",
            title = "Artist One",
            providerId = "netease",
            providerName = "网易云音乐",
            type = MediaRefType.Artist,
        )
        val track = track(durationMs = 180_000L).copy(
            albumItemId = "album-1",
            artistItems = listOf(artist),
        )
        val queue = PlaybackQueueState(
            queuePlaylistId = "playlist-1",
            lastPlaybackStartReason = PlaybackStartReason.PLAYLIST_REPLACE,
            playbackStartSequence = 1L,
        )

        recorder.onPlaybackState(PlaybackState(PlayerStatus.Playing, track, durationMs = 180_000L), queue)
        runCurrent()

        val record = sink.records.last()
        assertTrue(record.resources.any { it.relation == ListeningResourceRelationType.Artist })
        assertTrue(record.resources.any { it.relation == ListeningResourceRelationType.Album })
        assertTrue(record.resources.any { it.relation == ListeningResourceRelationType.PlaylistContext })
        assertEquals(ListeningStartReason.PlaylistReplace, record.startReason)
    }

    private fun track(durationMs: Long): MusicTrack = MusicTrack(
        id = "track-1",
        title = "Track One",
        artists = "Artist One",
        album = "Album One",
        source = "netease",
        sourceType = TrackSourceType.Provider,
        durationMs = durationMs,
    )
}

private class RecordingListeningHistorySink : ListeningHistorySink {
    val records = mutableListOf<ListeningHistoryRecord>()

    override suspend fun upsert(record: ListeningHistoryRecord) {
        records += record
    }
}
