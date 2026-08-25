package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackFeedbackOwnershipTest {
    @Test
    fun sleepTimerFeedbackIsOwnedByPlaybackPortAndDismissible() = runTest {
        val engine = FakePlaybackEngine()
        val compatibilityFeedback = mutableListOf<String>()
        val controller = PlaybackSleepTimerController(
            playbackEngine = engine,
            scope = this,
            currentTrackId = { null },
            nowMillis = { 0L },
            onFeedback = compatibilityFeedback::add,
        )

        controller.setSleepTimerDurationMinutes(30)

        assertEquals("请先播放一首歌曲", controller.feedback.value)
        assertEquals(listOf("请先播放一首歌曲"), compatibilityFeedback)
        controller.dismissFeedback("请先播放一首歌曲")
        assertNull(controller.feedback.value)
    }

    private class FakePlaybackEngine : PlaybackEngine {
        override val state: StateFlow<PlaybackState> = MutableStateFlow(PlaybackState())

        override fun play(track: MusicTrack, payload: PlaybackPayload) = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
        override fun seekTo(positionMs: Long) = Unit
    }
}
