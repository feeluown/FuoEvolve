package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class P2FeedbackParityTest {
    @Test
    fun queueFeedbackIsOwnedByPlaybackPortAndCanBeDismissed() = runTest {
        val current = testTrack("main:1")
        val next = testTrack("next:1")
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(current)
            mainQueueIndex = 0
        }
        val coordinator = PlaybackQueueCoordinator(
            queue = queue,
            scope = this,
            fallbackTrack = { null },
            playbackParts = { emptyList() },
            currentPartIndex = { -1 },
            startPlayback = { _, _, _ -> },
            stopPlayback = {},
            persistQueue = {},
            updateQueueState = {},
            appendFeatureQueue = { 0 },
            setTrackChangeDirection = {},
            setMessage = {},
        )

        coordinator.addToUpNext(next)

        assertEquals("已加入接下来播放：${next.title}", coordinator.feedback.value)
        coordinator.dismissFeedback(coordinator.feedback.value.orEmpty())
        assertNull(coordinator.feedback.value)
    }

    private fun testTrack(id: String) = MusicTrack(
        id = id,
        title = id,
        artists = "artist",
        album = "album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
    )
}
