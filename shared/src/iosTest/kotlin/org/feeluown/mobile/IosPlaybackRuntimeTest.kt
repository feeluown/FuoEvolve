package org.feeluown.mobile

import org.feeluown.mobile.playback.api.PlaybackSessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class IosPlaybackRuntimeTest {
    @Test
    fun resolutionFailureOverridesLoadingEngineForSameTrack() {
        val track = testTrack("track:1")
        val merged = mergeIosPlaybackRuntimeEngineState(
            engineState = PlaybackState(
                status = PlayerStatus.Loading,
                currentTrack = track,
                positionMs = 1_200,
                durationMs = 10_000,
            ),
            coordinatorState = PlaybackState(
                status = PlayerStatus.Error,
                currentTrack = track,
                errorMessage = "资源解析失败",
            ),
        )

        assertEquals(PlaybackSessionStatus.Error, merged.status)
        assertEquals("资源解析失败", merged.errorMessage)
        assertEquals(1_200, merged.positionMs)
        assertEquals(10_000, merged.durationMs)
    }

    @Test
    fun unrelatedCoordinatorErrorDoesNotOverrideEngineState() {
        val merged = mergeIosPlaybackRuntimeEngineState(
            engineState = PlaybackState(
                status = PlayerStatus.Loading,
                currentTrack = testTrack("track:1"),
            ),
            coordinatorState = PlaybackState(
                status = PlayerStatus.Error,
                currentTrack = testTrack("track:2"),
                errorMessage = "其它页面加载失败",
            ),
        )

        assertEquals(PlaybackSessionStatus.Loading, merged.status)
        assertEquals(null, merged.errorMessage)
    }

    private fun testTrack(id: String): MusicTrack = MusicTrack(
        id = id,
        title = "Test",
        artists = "Artist",
        album = "Album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
    )
}
