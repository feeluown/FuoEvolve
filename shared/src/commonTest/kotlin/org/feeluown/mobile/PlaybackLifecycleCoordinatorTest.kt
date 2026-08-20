package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PlaybackLifecycleCoordinatorTest {
    @Test
    fun playingThenEndedAutoAdvancesOnce() {
        val timer = FakeSleepTimer()
        var autoAdvanceCount = 0
        val coordinator = coordinator(timer) { autoAdvanceCount += 1 }
        val track = track("track:1")

        assertEquals(
            PlaybackEndAction.None,
            coordinator.evaluate(
                engineState = PlaybackState(status = PlayerStatus.Playing, currentTrack = track),
                currentQueueTrackId = track.id,
            ),
        )
        val endedAction = coordinator.evaluate(
            engineState = PlaybackState(status = PlayerStatus.Ended, currentTrack = track),
            currentQueueTrackId = track.id,
        )
        assertEquals(PlaybackEndAction.AutoAdvance, endedAction)
        coordinator.execute(endedAction)
        assertEquals(1, autoAdvanceCount)

        assertEquals(
            PlaybackEndAction.None,
            coordinator.evaluate(
                engineState = PlaybackState(status = PlayerStatus.Ended, currentTrack = track),
                currentQueueTrackId = track.id,
            ),
        )
        assertEquals(1, autoAdvanceCount)
    }

    @Test
    fun endOfTrackSleepTimerWinsOnFinalPlaybackPart() {
        val track = track("track:1")
        val timer = FakeSleepTimer(targetTrackId = track.id)
        var autoAdvanceCount = 0
        val coordinator = coordinator(timer) { autoAdvanceCount += 1 }
        val parts = listOf(
            PlaybackPart("part:1", "P1"),
            PlaybackPart("part:2", "P2"),
        )

        coordinator.evaluate(
            engineState = PlaybackState(status = PlayerStatus.Playing, currentTrack = track),
            currentQueueTrackId = track.id,
        )
        val action = coordinator.evaluate(
            engineState = PlaybackState(
                status = PlayerStatus.Ended,
                currentTrack = track,
                playbackParts = parts,
                currentPartIndex = 1,
            ),
            currentQueueTrackId = track.id,
        )

        assertEquals(PlaybackEndAction.CompleteSleepTimer, action)
        coordinator.execute(action)
        assertEquals(1, timer.completeCount)
        assertEquals(0, autoAdvanceCount)
    }

    @Test
    fun endOfTrackSleepTimerDoesNotBlockIntermediatePlaybackPartAdvance() {
        val track = track("track:1")
        val timer = FakeSleepTimer(targetTrackId = track.id)
        var autoAdvanceCount = 0
        val coordinator = coordinator(timer) { autoAdvanceCount += 1 }
        val parts = listOf(
            PlaybackPart("part:1", "P1"),
            PlaybackPart("part:2", "P2"),
        )

        coordinator.evaluate(
            engineState = PlaybackState(status = PlayerStatus.Playing, currentTrack = track),
            currentQueueTrackId = track.id,
        )
        val action = coordinator.evaluate(
            engineState = PlaybackState(
                status = PlayerStatus.Ended,
                currentTrack = track,
                playbackParts = parts,
                currentPartIndex = 0,
            ),
            currentQueueTrackId = track.id,
        )

        assertEquals(PlaybackEndAction.AutoAdvance, action)
        coordinator.execute(action)
        assertEquals(0, timer.completeCount)
        assertEquals(1, autoAdvanceCount)
    }

    @Test
    fun fallbackPlaybackPartsPreserveFinalPartDecision() {
        val track = track("track:1")
        val timer = FakeSleepTimer(targetTrackId = track.id)
        val fallbackParts = listOf(
            PlaybackPart("part:1", "P1"),
            PlaybackPart("part:2", "P2"),
        )
        val coordinator = PlaybackLifecycleCoordinator(
            sleepTimer = timer,
            fallbackPlaybackParts = { fallbackParts },
            fallbackCurrentPartIndex = { 1 },
            autoAdvance = {},
        )

        coordinator.evaluate(
            engineState = PlaybackState(status = PlayerStatus.Playing, currentTrack = track),
            currentQueueTrackId = track.id,
        )
        val action = coordinator.evaluate(
            engineState = PlaybackState(
                status = PlayerStatus.Ended,
                currentTrack = track,
                playbackParts = emptyList(),
                currentPartIndex = -1,
            ),
            currentQueueTrackId = track.id,
        )

        assertEquals(PlaybackEndAction.CompleteSleepTimer, action)
    }

    @Test
    fun nullQueueTrackClearsEndOfTrackTimer() {
        val track = track("track:1")
        val timer = FakeSleepTimer(targetTrackId = track.id)
        val coordinator = coordinator(timer) {}

        coordinator.evaluate(
            engineState = PlaybackState(status = PlayerStatus.Paused, currentTrack = null),
            currentQueueTrackId = null,
        )

        assertFalse(timer.shouldCompleteEndOfTrack(track.id, isFinalPlaybackPart = true))
    }

    private fun coordinator(
        timer: FakeSleepTimer,
        autoAdvance: () -> Unit,
    ) = PlaybackLifecycleCoordinator(
        sleepTimer = timer,
        fallbackPlaybackParts = { emptyList() },
        fallbackCurrentPartIndex = { -1 },
        autoAdvance = autoAdvance,
    )

    private fun track(id: String): MusicTrack = MusicTrack(
        id = id,
        title = id,
        artists = "Artist",
        album = "Album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
    )

    private class FakeSleepTimer(
        private var targetTrackId: String? = null,
    ) : PlaybackEndSleepTimer {
        var completeCount = 0
            private set

        override fun onTrackChanged(trackId: String?) {
            if (targetTrackId != null && targetTrackId != trackId) {
                targetTrackId = null
            }
        }

        override fun shouldCompleteEndOfTrack(
            trackId: String,
            isFinalPlaybackPart: Boolean,
        ): Boolean = targetTrackId == trackId && isFinalPlaybackPart

        override fun completeEndOfTrack() {
            completeCount += 1
            targetTrackId = null
        }
    }
}
