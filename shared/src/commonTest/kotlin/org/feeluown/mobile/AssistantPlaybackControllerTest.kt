package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantPlaybackControllerTest {
    @Test
    fun searchRanksTitleAndArtistThenStartsExistingPlaybackPath() = runTest {
        val wrongArtist = track(id = "1", title = "晴天", artists = "另一位歌手", providerId = "netease")
        val expected = track(id = "2", title = "晴天", artists = "周杰伦", providerId = "qqmusic")
        val providerQueries = mutableListOf<Pair<String, String?>>()
        var started: MusicTrack? = null
        val controller = AssistantPlaybackController(
            providerSearchRepository = object : ProviderSearchRepository {
                override suspend fun search(keyword: String, providerId: String?): List<MusicTrack> {
                    providerQueries += keyword to providerId
                    return when (providerId) {
                        "netease" -> listOf(wrongArtist)
                        "qqmusic" -> listOf(expected)
                        else -> emptyList()
                    }
                }
            },
            localSearch = { emptyList() },
            providerIdsForSearch = { listOf("netease", "qqmusic") },
            startPlayback = { started = it },
        )

        assertTrue(controller.playFromSearch("  周杰伦的晴天  "))

        assertEquals(expected, started)
        assertEquals(
            listOf("周杰伦的晴天" to "netease", "周杰伦的晴天" to "qqmusic"),
            providerQueries,
        )
    }

    @Test
    fun exactLocalTitleWinsEqualProviderResult() = runTest {
        val local = track(
            id = "local:1",
            title = "Bohemian Rhapsody",
            artists = "Queen",
            source = "local",
            sourceType = TrackSourceType.LocalMediaStore,
        )
        val provider = track(id = "provider:1", title = "Bohemian Rhapsody", artists = "Queen")
        var started: MusicTrack? = null
        val controller = AssistantPlaybackController(
            providerSearchRepository = providerRepository(listOf(provider)),
            localSearch = { listOf(local) },
            providerIdsForSearch = { listOf("netease") },
            startPlayback = { started = it },
        )

        assertTrue(controller.playFromSearch("Bohemian Rhapsody"))
        assertEquals(local, started)
    }

    @Test
    fun blankOrMissingSearchDoesNotStartPlayback() = runTest {
        var started: MusicTrack? = null
        val controller = AssistantPlaybackController(
            providerSearchRepository = providerRepository(emptyList()),
            localSearch = { emptyList() },
            providerIdsForSearch = { listOf("netease") },
            startPlayback = { started = it },
        )

        assertFalse(controller.playFromSearch("   "))
        assertFalse(controller.playFromSearch("不存在的歌"))
        assertNull(started)
    }

    private fun providerRepository(tracks: List<MusicTrack>) = object : ProviderSearchRepository {
        override suspend fun search(keyword: String, providerId: String?): List<MusicTrack> = tracks
    }

    private fun track(
        id: String,
        title: String,
        artists: String,
        providerId: String = "netease",
        source: String = providerId,
        sourceType: TrackSourceType = TrackSourceType.Provider,
    ): MusicTrack = MusicTrack(
        id = id,
        title = title,
        artists = artists,
        album = "",
        source = source,
        sourceType = sourceType,
        providerId = providerId.takeIf { sourceType == TrackSourceType.Provider },
        providerName = providerId.takeIf { sourceType == TrackSourceType.Provider },
    )
}
