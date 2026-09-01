package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidPlaybackResumePolicyTest {
    @Test
    fun onlyEstablishedPlaybackIsDurable() {
        assertTrue(PlayerStatus.Playing.isDurablePlaybackResumeStatus())
        assertTrue(PlayerStatus.Paused.isDurablePlaybackResumeStatus())
        assertFalse(PlayerStatus.Loading.isDurablePlaybackResumeStatus())
        assertFalse(PlayerStatus.Idle.isDurablePlaybackResumeStatus())
        assertFalse(PlayerStatus.Error.isDurablePlaybackResumeStatus())
        assertFalse(PlayerStatus.Ended.isDurablePlaybackResumeStatus())
    }

    @Test
    fun onlyNewLogicalSelectionsClearDurableResume() {
        assertTrue(PlaybackStartReason.USER_SELECTION.clearsDurablePlaybackResume)
        assertTrue(PlaybackStartReason.PLAYLIST_REPLACE.clearsDurablePlaybackResume)
        assertFalse(PlaybackStartReason.SOURCE_SWITCH.clearsDurablePlaybackResume)
        assertFalse(PlaybackStartReason.AUTO_NEXT.clearsDurablePlaybackResume)
        assertFalse(PlaybackStartReason.RESUME.clearsDurablePlaybackResume)
        assertFalse(PlaybackStartReason.RESTORE_SESSION.clearsDurablePlaybackResume)
        assertFalse(PlaybackStartReason.RECOVERY.clearsDurablePlaybackResume)
    }
}
