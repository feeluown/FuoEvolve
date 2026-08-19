package org.feeluown.mobile

import org.feeluown.mobile.playback.api.PlaybackSessionStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class IosPlaybackRuntimeTest {
    @Test
    fun playbackStartFailureOverridesLoadingEngineForSameTrack() {
        val track = testTrack("track:1")
        val merged = mergePlaybackStartFailure(
            engineState = PlaybackState(
                status = PlayerStatus.Loading,
                currentTrack = track,
                positionMs = 1_200,
                durationMs = 10_000,
                bufferedMs = 4_000,
            ),
            startFailure = PlaybackStartFailure(
                trackId = track.id,
                message = "资源解析失败",
            ),
        )

        assertEquals(PlaybackSessionStatus.Error, merged.status)
        assertEquals("资源解析失败", merged.errorMessage)
        assertEquals(1_200, merged.positionMs)
        assertEquals(10_000, merged.durationMs)
        assertEquals(4_000, merged.bufferedMs)
    }

    @Test
    fun unrelatedPlaybackStartFailureDoesNotOverrideEngineState() {
        val merged = mergePlaybackStartFailure(
            engineState = PlaybackState(
                status = PlayerStatus.Loading,
                currentTrack = testTrack("track:1"),
            ),
            startFailure = PlaybackStartFailure(
                trackId = "track:2",
                message = "其它歌曲解析失败",
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
