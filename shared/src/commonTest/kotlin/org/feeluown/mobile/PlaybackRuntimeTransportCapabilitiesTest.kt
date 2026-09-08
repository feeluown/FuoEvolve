package org.feeluown.mobile

import kotlin.test.Test
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
        durationMs = 100_000L,
        providerId = id,
        providerName = "test",
    )
}
