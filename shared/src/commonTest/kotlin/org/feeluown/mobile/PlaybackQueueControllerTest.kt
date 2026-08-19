package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackQueueControllerTest {
    @Test
    fun snapshotAndRestorePreserveQueuePolicyState() {
        val first = track("netease:1", "First")
        val second = track("netease:2", "Second")
        val pending = track("qqmusic:3", "Pending")
        val source = PlaybackQueueController().apply {
            mainQueue = listOf(first, second)
            originalMainQueue = listOf(second, first)
            upNextQueue = listOf(pending)
            mainQueueIndex = 1
            shuffleEnabled = true
            repeatMode = RepeatMode.SINGLE
            isFmQueue = true
            shuffleBeforeFm = false
        }

        val restored = PlaybackQueueController().apply {
            restore(source.snapshot())
        }

        assertEquals(listOf(first, second), restored.mainQueue)
        assertEquals(listOf(second, first), restored.originalMainQueue)
        assertEquals(listOf(pending), restored.upNextQueue)
        assertEquals(1, restored.mainQueueIndex)
        assertTrue(restored.shuffleEnabled)
        assertEquals(RepeatMode.SINGLE, restored.repeatMode)
        assertTrue(restored.isFmQueue)
        assertFalse(restored.shuffleBeforeFm ?: true)
        assertEquals(second, restored.currentTrack())
    }

    @Test
    fun displayQueueKeepsCurrentThenUpNextThenRemainingMainQueue() {
        val first = track("netease:1", "First")
        val second = track("netease:2", "Second")
        val third = track("netease:3", "Third")
        val pending = track("qqmusic:4", "Pending")
        val controller = PlaybackQueueController().apply {
            mainQueue = listOf(first, second, third)
            mainQueueIndex = 1
            upNextQueue = listOf(pending)
        }

        assertEquals(listOf(second, pending, third), controller.displayQueue())
        assertEquals(0, controller.displayQueueIndex())
    }

    private fun track(id: String, title: String) = MusicTrack(
        id = id,
        title = title,
        artists = "Artist",
        album = "Album",
        source = id.substringBefore(':'),
        sourceType = TrackSourceType.Provider,
        providerId = id,
    )
}
