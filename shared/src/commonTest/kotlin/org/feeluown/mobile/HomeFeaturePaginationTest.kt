package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeFeaturePaginationTest {
    @Test
    fun playAllPaginationLoadsEveryPageAndDeduplicatesTracks() = runTest {
        val feature = ProviderFeature(
            id = "netease_recommend",
            providerId = "netease",
            providerName = "网易云音乐",
            title = "推荐歌曲",
            category = ProviderFeatureCategory.Recommend,
            contentType = ProviderContentType.Songs,
            requiresLogin = false,
        )
        val first = track("1")
        val second = track("2")
        val third = track("3")
        val fourth = track("4")
        val requestedOffsets = mutableListOf<Int>()

        val result = loadAllHomeFeatureTracks(
            initial = ProviderContentSection(
                feature = feature,
                tracks = listOf(first, second),
                nextOffset = 2,
                hasMore = true,
            ),
        ) { _, offset ->
            requestedOffsets += offset
            when (offset) {
                2 -> ProviderContentSection(
                    feature = feature,
                    tracks = listOf(second, third),
                    nextOffset = 4,
                    hasMore = true,
                )
                4 -> ProviderContentSection(
                    feature = feature,
                    tracks = listOf(fourth),
                    nextOffset = 5,
                    hasMore = false,
                )
                else -> error("unexpected offset: $offset")
            }
        }

        assertEquals(listOf("1", "2", "3", "4"), result.map { it.id })
        assertEquals(listOf(2, 4), requestedOffsets)
    }

    private fun track(id: String) = MusicTrack(
        id = id,
        title = "Track $id",
        artists = "Artist",
        album = "Album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
    )
}
