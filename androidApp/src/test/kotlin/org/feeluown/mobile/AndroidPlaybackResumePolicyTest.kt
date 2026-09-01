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
}
