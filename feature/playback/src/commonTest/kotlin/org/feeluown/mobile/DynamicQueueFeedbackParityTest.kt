package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DynamicQueueFeedbackParityTest {
    @Test
    fun appendFailureIsNotReplacedByNoMoreSongsFeedback() = runTest {
        val current = MusicTrack(
            id = "qqmusic:1",
            title = "Current",
            artists = "Artist",
            album = "Album",
            source = "qqmusic",
            sourceType = TrackSourceType.Provider,
        )
        val feature = ProviderFeature(
            id = "qqmusic_radio",
            providerId = "qqmusic",
            providerName = "QQ 音乐",
            title = "私人 FM",
            category = ProviderFeatureCategory.Recommend,
            contentType = ProviderContentType.Songs,
            requiresLogin = true,
        )
        val queue = PlaybackQueueController().apply {
            mainQueue = listOf(current)
            mainQueueIndex = 0
            queueFeature = feature
            isFmQueue = true
        }
        val feedback = MutableStateFlow<String?>(null)
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
            appendFeatureQueue = {
                feedback.value = "私人 FM 加载超时，请重试"
                FEATURE_QUEUE_APPEND_FAILED
            },
            setTrackChangeDirection = {},
            setMessage = {},
            feedbackState = feedback,
        )

        coordinator.next()
        runCurrent()

        assertEquals("私人 FM 加载超时，请重试", feedback.value)
    }
}
