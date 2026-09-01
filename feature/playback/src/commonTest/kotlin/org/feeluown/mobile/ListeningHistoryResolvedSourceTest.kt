package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ListeningHistoryResolvedSourceTest {
    @Test
    fun replacementIsRecordedAsResolvedRelationWhilePrimaryStaysLogical() = runTest {
        val sink = RecordingListeningSink()
        var wallClock = 1_000L
        var monotonicClock = 0L
        val recorder = ListeningHistoryRecorder(
            sink = sink,
            scope = backgroundScope,
            nowMillis = { wallClock },
            monotonicMillis = { monotonicClock },
        )
        val logical = MusicTrack(
            id = "netease:logical",
            title = "Logical",
            artists = "Artist",
            album = "",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            providerId = "netease:logical",
            durationMs = 120_000L,
        )
        val queueState = PlaybackQueueState(
            mainQueue = listOf(logical),
            mainQueueIndex = 0,
            lastPlaybackStartReason = PlaybackStartReason.USER_SELECTION,
            playbackStartSequence = 1L,
        )
        recorder.onPlaybackState(
            state = PlaybackState(
                status = PlayerStatus.Playing,
                currentTrack = logical,
                resolvedSource = ResolvedPlaybackSource(
                    trackId = "qqmusic:physical",
                    title = "Physical",
                    artists = "Artist",
                    album = "",
                    source = "qqmusic",
                    sourceType = TrackSourceType.Provider,
                    providerName = "QQ Music",
                    isReplacement = true,
                ),
                durationMs = 120_000L,
            ),
            queueState = queueState,
        )
        testScheduler.runCurrent()

        val record = assertNotNull(sink.records.lastOrNull())
        assertEquals("Track:7:netease:netease:logical", record.primaryResourceKey)
        val resolved = assertNotNull(
            record.resources.firstOrNull { it.relation == ListeningResourceRelationType.ResolvedSource }
        )
        assertEquals("qqmusic", resolved.resource.sourceId)
        assertEquals("qqmusic:physical", resolved.resource.sourceResourceId)
    }

    private class RecordingListeningSink : ListeningHistorySink {
        val records = mutableListOf<ListeningHistoryRecord>()
        override suspend fun upsert(record: ListeningHistoryRecord) {
            records += record
        }
    }
}
