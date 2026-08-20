package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackUiOwnersTest {
    @Test
    fun navigationOwnsFullPlayerAndQueueVisibility() {
        val navigation = DefaultPlaybackNavigationPort()

        navigation.toggleQueue()
        assertFalse(navigation.isQueueOpen)

        navigation.openFullPlayer()
        navigation.toggleQueue()
        assertTrue(navigation.isFullPlayerOpen)
        assertTrue(navigation.isQueueOpen)

        navigation.closeFullPlayer()
        assertFalse(navigation.isFullPlayerOpen)
        assertFalse(navigation.isQueueOpen)
    }

    @Test
    fun presentationFallsBackToRestoredQueueTrackWhenEngineIsEmpty() {
        val restored = track("track:1").copy(title = "Restored title")

        assertEquals(
            restored,
            resolvePlaybackPresentationTrack(
                engineTrack = null,
                queueTrack = restored,
            ),
        )
    }

    @Test
    fun presentationPrefersQueueMetadataForSameTrackIdentity() {
        val engineTrack = track("track:1").copy(title = "Stale title")
        val editedQueueTrack = engineTrack.copy(title = "Edited local title")

        assertEquals(
            editedQueueTrack,
            resolvePlaybackPresentationTrack(
                engineTrack = engineTrack,
                queueTrack = editedQueueTrack,
            ),
        )
    }

    @Test
    fun presentationKeepsEngineTrackDuringIdentityTransition() {
        val engineTrack = track("track:1")
        val queueTrack = track("track:2")

        assertEquals(
            engineTrack,
            resolvePlaybackPresentationTrack(
                engineTrack = engineTrack,
                queueTrack = queueTrack,
            ),
        )
    }

    private fun track(id: String): MusicTrack = MusicTrack(
        id = id,
        title = id,
        artists = "Artist",
        album = "Album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
    )
}
