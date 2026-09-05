package org.feeluown.mobile

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class PlaybackQueueEntryShuffleTest {
    @Test
    fun shuffleWithDuplicateTrackPreservesCurrentOccurrence() = runTest {
        val firstA = track("track:a", "A first")
        val trackB = track("track:b", "B")
        val secondA = track("track:a", "A second")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(firstA, trackB, secondA)
            mainQueueIndex = 2
        }
        val currentEntryId = assertNotNull(queue.currentQueueEntryId())
        val coordinator = coordinator(queue) { it.reversed() }

        coordinator.toggleShuffle()

        assertEquals(3, queue.mainQueue.size)
        assertEquals(secondA, queue.currentTrack())
        assertEquals(currentEntryId, queue.currentQueueEntryId())
        assertEquals(currentEntryId, queue.mainQueueEntries().first().id)
        assertEquals(
            queue.mainQueueEntries().map { it.id }.toSet(),
            queue.originalMainQueueEntries().map { it.id }.toSet(),
        )

        coordinator.toggleShuffle()

        assertEquals(listOf(firstA, trackB, secondA), queue.mainQueue)
        assertEquals(2, queue.mainQueueIndex)
        assertEquals(secondA, queue.currentTrack())
        assertEquals(currentEntryId, queue.currentQueueEntryId())
    }

    @Test
    fun playingUpNextKeepsPendingOccurrenceIdentity() = runTest {
        val current = track("track:a", "Current A")
        val pending = track("track:a", "Pending A")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(current)
            mainQueueIndex = 0
            upNextQueue = listOf(pending)
        }
        val pendingEntryId = assertNotNull(queue.displayQueueEntries().getOrNull(1)).id
        var started: MusicTrack? = null
        val coordinator = coordinator(queue, onStart = { started = it })

        coordinator.next()

        assertEquals(pending, started)
        assertEquals(pending, queue.currentTrack())
        assertEquals(pendingEntryId, queue.currentQueueEntryId())
    }

    private fun TestScope.coordinator(
        queue: PlaybackQueueController,
        shuffleTracks: (List<MusicTrack>) -> List<MusicTrack> = { it },
        onStart: (MusicTrack) -> Unit = {},
    ) = PlaybackQueueCoordinator(
        queue = queue,
        scope = this,
        fallbackTrack = { null },
        playbackParts = { emptyList() },
        currentPartIndex = { -1 },
        startPlayback = { track, _, _ -> onStart(track) },
        stopPlayback = {},
        persistQueue = {},
        updateQueueState = {},
        appendFeatureQueue = { 0 },
        setTrackChangeDirection = {},
        setMessage = {},
        shuffleTracks = shuffleTracks,
    )

    private fun track(id: String, title: String) = MusicTrack(
        id = id,
        title = title,
        artists = "Artist",
        album = "Album",
        source = "test",
        sourceType = TrackSourceType.Provider,
        providerId = id,
    )
}
