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

        val restored = PlaybackQueueController()
        assertTrue(restored.restore(source.snapshot()))

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
    fun delayedStartupRestoreDoesNotOverwriteNewPlaylistSelection() {
        val restoredTrack = track("netease:old", "Restored")
        val oldSnapshot = PlaybackQueueController().apply {
            mainQueue = listOf(restoredTrack)
            mainQueueIndex = 0
        }.snapshot()
        val firstNew = track("qqmusic:new-1", "New 1")
        val secondNew = track("qqmusic:new-2", "New 2")
        val controller = PlaybackQueueController().apply {
            mainQueue = listOf(firstNew, secondNew)
            mainQueueIndex = 0
            queuePlaylistId = "playlist:new"
            markNextPlaybackStart(PlaybackStartReason.PLAYLIST_REPLACE)
        }

        assertFalse(controller.restore(oldSnapshot))

        assertEquals(listOf(firstNew, secondNew), controller.mainQueue)
        assertEquals(firstNew, controller.currentTrack())
        assertEquals("playlist:new", controller.queuePlaylistId)
        assertEquals(PlaybackStartReason.PLAYLIST_REPLACE, controller.consumePlaybackStartReason())
    }

    @Test
    fun delayedStartupRestoreDoesNotOverwriteQueuePolicyMutation() {
        val restoredTrack = track("netease:old", "Restored")
        val oldSnapshot = PlaybackQueueController().apply {
            mainQueue = listOf(restoredTrack)
            mainQueueIndex = 0
        }.snapshot()
        val controller = PlaybackQueueController().apply {
            repeatMode = RepeatMode.OFF
        }

        assertFalse(controller.restore(oldSnapshot))

        assertTrue(controller.mainQueue.isEmpty())
        assertEquals(-1, controller.mainQueueIndex)
        assertEquals(RepeatMode.OFF, controller.repeatMode)
    }

    @Test
    fun mutationReturnedToDefaultStillRejectsDelayedStartupRestore() {
        val restoredTrack = track("netease:old", "Restored")
        val oldSnapshot = PlaybackQueueController().apply {
            mainQueue = listOf(restoredTrack)
            mainQueueIndex = 0
        }.snapshot()
        val controller = PlaybackQueueController().apply {
            shuffleEnabled = true
            shuffleEnabled = false
        }

        assertFalse(controller.restore(oldSnapshot))
        assertTrue(controller.mainQueue.isEmpty())
        assertFalse(controller.shuffleEnabled)
    }

    @Test
    fun noOpQueueClearMutationStillRejectsDelayedStartupRestore() {
        val restoredTrack = track("netease:old", "Restored")
        val oldSnapshot = PlaybackQueueController().apply {
            mainQueue = listOf(restoredTrack)
            mainQueueIndex = 0
        }.snapshot()
        val controller = PlaybackQueueController().apply {
            mainQueue = emptyList()
            mainQueueIndex = -1
        }

        assertFalse(controller.restore(oldSnapshot))
        assertTrue(controller.mainQueue.isEmpty())
        assertEquals(-1, controller.mainQueueIndex)
    }

    @Test
    fun resumeIntentStillAllowsStartupQueueRestore() {
        val restoredTrack = track("netease:old", "Restored")
        val oldSnapshot = PlaybackQueueController().apply {
            mainQueue = listOf(restoredTrack)
            mainQueueIndex = 0
        }.snapshot()
        val controller = PlaybackQueueController().apply {
            markNextPlaybackStart(PlaybackStartReason.RESUME)
        }

        assertTrue(controller.restore(oldSnapshot))

        assertEquals(listOf(restoredTrack), controller.mainQueue)
        assertEquals(restoredTrack, controller.currentTrack())
        assertEquals(PlaybackStartReason.AUTO_NEXT, controller.consumePlaybackStartReason())
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
