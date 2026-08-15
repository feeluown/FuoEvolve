package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackQueueRestorationTest {
    @Test
    fun currentMainQueueTrackLeavesSnapshotUnchanged() {
        val first = track("song:1")
        val second = track("song:2")
        val snapshot = PlaybackQueueSnapshot(
            mainQueue = listOf(first, second),
            queueIndex = 0,
            shuffleEnabled = true,
            repeatMode = RepeatMode.SINGLE,
        )

        val restored = snapshot.reconcileRestoredPlayback(
            plan = plan(first, second),
            currentTrack = first,
        )

        assertEquals(snapshot, restored)
    }

    @Test
    fun activeUpNextTrackIsReinsertedForControllerReconciliation() {
        val first = track("song:1")
        val second = track("song:2")
        val activeUpNext = track("song:up-next")
        val pendingUpNext = track("song:pending")
        val snapshot = PlaybackQueueSnapshot(
            mainQueue = listOf(first, second),
            upNextQueue = listOf(pendingUpNext),
            queueIndex = 0,
            shuffleEnabled = true,
            repeatMode = RepeatMode.QUEUE,
        )

        val restored = snapshot.reconcileRestoredPlayback(
            plan = plan(activeUpNext, pendingUpNext, second),
            currentTrack = activeUpNext,
        )

        assertEquals(listOf(activeUpNext, pendingUpNext), restored.upNextQueue)
        assertEquals(snapshot.mainQueue, restored.mainQueue)
        assertEquals(snapshot.queueIndex, restored.queueIndex)
        assertEquals(true, restored.shuffleEnabled)
        assertEquals(RepeatMode.QUEUE, restored.repeatMode)
    }

    @Test
    fun emptyLegacySnapshotRecoversCurrentAndLookAheadFromPlaybackPlan() {
        val alreadyPlayed = track("song:old")
        val current = track("song:current")
        val next = track("song:next")
        val later = track("song:later")
        val snapshot = PlaybackQueueSnapshot(
            shuffleEnabled = true,
            repeatMode = RepeatMode.OFF,
        )

        val restored = snapshot.reconcileRestoredPlayback(
            plan = plan(alreadyPlayed, current, next, later),
            currentTrack = current,
        )

        assertEquals(listOf(current, next, later), restored.mainQueue)
        assertEquals(0, restored.queueIndex)
        assertEquals(true, restored.shuffleEnabled)
        assertEquals(RepeatMode.OFF, restored.repeatMode)
    }

    private fun plan(vararg tracks: MusicTrack): PlaybackPlan = PlaybackPlan(
        generation = 1,
        requests = tracks.map { PlaybackRequest(it) },
    )

    private fun track(id: String): MusicTrack = MusicTrack(
        id = id,
        title = id,
        artists = "Artist",
        album = "Album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
        providerId = id,
        providerName = "NetEase",
    )
}
