package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackRuntimeTransportCapabilitiesTest {
    @Test
    fun previousUsesCanonicalQueuePositionInsteadOfDisplayQueueIndex() {
        val first = track("first")
        val current = track("current")
        val next = track("next")
        val queueState = PlaybackQueueState(
            mainQueue = listOf(first, current, next),
            mainQueueIndex = 1,
            repeatMode = RepeatMode.OFF,
        )
        val playbackState = PlaybackState(
            status = PlayerStatus.Playing,
            currentTrack = current,
            queue = listOf(current, next),
            queueIndex = 0,
        )

        assertTrue(playbackRuntimeCanGoPrevious(playbackState, queueState))
        assertTrue(playbackRuntimeCanGoNext(playbackState, queueState))
    }

    @Test
    fun canonicalQueueKeepsPastCurrentUpNextAndFutureOrder() {
        val first = track("first")
        val current = track("current")
        val next = track("next")
        val upNextA = track("up-next-a")
        val upNextB = track("up-next-b")
        val queue = playbackRuntimeCanonicalQueue(
            PlaybackQueueState(
                mainQueue = listOf(first, current, next),
                mainQueueIndex = 1,
                upNextQueue = listOf(upNextA, upNextB),
            ),
        )

        assertEquals(listOf("first", "current", "up-next-a", "up-next-b", "next"), queue.tracks.map { it.id })
        assertEquals(1, queue.index)
    }

    @Test
    fun canonicalQueuePlacesActiveUpNextAtItsRealPosition() {
        val first = track("first")
        val current = track("current")
        val next = track("next")
        val activeUpNext = track("active-up-next")
        val pendingUpNext = track("pending-up-next")
        val queue = playbackRuntimeCanonicalQueue(
            PlaybackQueueState(
                mainQueue = listOf(first, current, next),
                mainQueueIndex = 1,
                currentUpNextTrack = activeUpNext,
                currentIsUpNext = true,
                upNextQueue = listOf(pendingUpNext),
            ),
        )

        assertEquals(listOf("first", "current", "active-up-next", "pending-up-next", "next"), queue.tracks.map { it.id })
        assertEquals(2, queue.index)
    }

    @Test
    fun repeatQueueKeepsTransportAvailableAtQueueEdges() {
        val only = track("only")
        val queueState = PlaybackQueueState(
            mainQueue = listOf(only),
            mainQueueIndex = 0,
            repeatMode = RepeatMode.QUEUE,
        )
        val playbackState = PlaybackState(
            status = PlayerStatus.Playing,
            currentTrack = only,
            queue = listOf(only),
            queueIndex = 0,
        )

        assertTrue(playbackRuntimeCanGoPrevious(playbackState, queueState))
        assertTrue(playbackRuntimeCanGoNext(playbackState, queueState))
    }

    @Test
    fun currentUpNextCanReturnToMainQueueAndContinueForward() {
        val base = track("base")
        val pending = track("pending")
        val next = track("next")
        val queueState = PlaybackQueueState(
            mainQueue = listOf(base, next),
            mainQueueIndex = 0,
            currentUpNextTrack = pending,
            currentIsUpNext = true,
            repeatMode = RepeatMode.OFF,
        )
        val playbackState = PlaybackState(
            status = PlayerStatus.Playing,
            currentTrack = pending,
            queue = listOf(pending, next),
            queueIndex = 0,
        )

        assertTrue(playbackRuntimeCanGoPrevious(playbackState, queueState))
        assertTrue(playbackRuntimeCanGoNext(playbackState, queueState))
    }

    @Test
    fun queuedButIdlePlaybackCanStartWithNext() {
        val first = track("first")
        val queueState = PlaybackQueueState(
            mainQueue = listOf(first),
            mainQueueIndex = -1,
            repeatMode = RepeatMode.OFF,
        )

        assertTrue(playbackRuntimeCanGoNext(PlaybackState(), queueState))
        assertFalse(playbackRuntimeCanGoPrevious(PlaybackState(), queueState))
    }

    @Test
    fun multipartTrackExposesPartNavigation() {
        val current = track("multipart")
        val queueState = PlaybackQueueState(
            mainQueue = listOf(current),
            mainQueueIndex = 0,
            repeatMode = RepeatMode.OFF,
        )
        val parts = listOf(
            PlaybackPart(id = "p1", title = "Part 1"),
            PlaybackPart(id = "p2", title = "Part 2"),
            PlaybackPart(id = "p3", title = "Part 3"),
        )

        val firstPart = PlaybackState(
            status = PlayerStatus.Playing,
            currentTrack = current,
            playbackParts = parts,
            currentPartIndex = 0,
        )
        assertTrue(playbackRuntimeCanGoNext(firstPart, queueState))
        assertFalse(playbackRuntimeCanGoPrevious(firstPart, queueState))

        val middlePart = firstPart.copy(currentPartIndex = 1)
        assertTrue(playbackRuntimeCanGoNext(middlePart, queueState))
        assertTrue(playbackRuntimeCanGoPrevious(middlePart, queueState))
    }

    @Test
    fun emptyPlaybackHasNoQueueTransport() {
        val playbackState = PlaybackState()
        val queueState = PlaybackQueueState(repeatMode = RepeatMode.QUEUE)

        assertFalse(playbackRuntimeCanGoPrevious(playbackState, queueState))
        assertFalse(playbackRuntimeCanGoNext(playbackState, queueState))
    }

    private fun track(id: String) = MusicTrack(
        id = id,
        title = "Track $id",
        artists = "Artist",
        album = "Album",
        source = "test",
        sourceType = TrackSourceType.Provider,
        durationMs = 100_000L,
        providerId = id,
        providerName = "test",
    )
}
