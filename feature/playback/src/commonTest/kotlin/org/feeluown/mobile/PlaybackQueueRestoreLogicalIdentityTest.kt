package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackQueueRestoreLogicalIdentityTest {
    @Test
    fun restoreNormalizesLegacyReplacementDecoratedSnapshot() {
        val legacy = MusicTrack(
            id = "netease:logical",
            title = "Logical",
            artists = "Artist",
            album = "",
            source = "netease",
            sourceType = TrackSourceType.Provider,
            providerId = "netease:logical",
            isSmartReplacement = true,
            originalId = "netease:logical",
            originalTitle = "Logical",
            originalArtists = "Artist",
            originalSource = "netease",
            replacementId = "bilibili:physical",
            replacementTitle = "Physical",
            replacementArtists = "Artist",
            replacementSource = "bilibili",
        )
        val controller = PlaybackQueueController()

        assertTrue(
            controller.restore(
                PlaybackQueueSnapshot(
                    mainQueue = listOf(legacy),
                    originalMainQueue = listOf(legacy),
                    queueIndex = 0,
                )
            )
        )

        val current = requireNotNull(controller.currentTrack())
        assertEquals("netease:logical", current.id)
        assertEquals("netease", current.source)
        assertFalse(current.isSmartReplacement)
        assertNull(current.replacementId)
        assertEquals(current, controller.snapshot().mainQueue.single())
    }
}
