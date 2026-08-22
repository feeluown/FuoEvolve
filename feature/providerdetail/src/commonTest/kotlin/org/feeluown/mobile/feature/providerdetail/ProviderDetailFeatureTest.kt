package org.feeluown.mobile.feature.providerdetail

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderDetailFeatureTest {
    @Test
    fun featureDetailPrefetchMergesPagesAndPlayAllUsesCompleteQueue() = runTest {
        val port = FeaturePort()
        val owner = createProviderFeatureDetailFeatureOwner(port, backgroundScope)
        val feature = FakeFeature("daily", "每日推荐", "netease")

        owner.activate(feature)
        runCurrent()
        assertEquals(listOf("a", "b"), owner.state.value.tracks.map(FakeTrack::id))
        assertTrue(owner.state.value.hasMore)

        owner.prefetchIfNeeded(1)
        runCurrent()
        assertEquals(listOf("a", "b", "c"), owner.state.value.tracks.map(FakeTrack::id))
        assertFalse(owner.state.value.hasMore)

        owner.playAll()
        runCurrent()
        assertEquals(listOf("a", "b", "c"), port.played.map(FakeTrack::id))
    }

    @Test
    fun playlistPermissionsAndRemovalStayOwnedByFeature() = runTest {
        val port = PlaylistPort()
        val owner = createProviderPlaylistDetailFeatureOwner(port, backgroundScope)
        val playlist = FakePlaylist("p1", "我的歌单", "qqmusic")
        val track = FakeTrack("t1", "歌曲", "qqmusic")

        owner.activate(playlist, FakeCategory.Mine)
        runCurrent()

        assertTrue(owner.canRemove(track))
        assertTrue(owner.canDelete())

        owner.remove(track)
        runCurrent()

        assertTrue(owner.state.value.tracks.isEmpty())
        assertEquals(listOf("qqmusic"), port.mutations)
    }

    @Test
    fun videoFullscreenIsFeatureLocalState() = runTest {
        val port = VideoPort()
        val owner = createProviderVideoDetailFeatureOwner(port, backgroundScope)
        val video = FakeVideo("v1", "MV", "netease")

        owner.activate(video)
        runCurrent()
        assertEquals("stream:v1", owner.state.value.payload)

        owner.toggleFullscreen()
        assertTrue(owner.state.value.isFullscreen)
    }

    private data class FakeFeature(val id: String, val title: String, val providerId: String)
    private data class FakeTrack(val id: String, val title: String = id, val source: String = "")
    private data class FakeContent(
        val tracks: List<FakeTrack>,
        val nextOffset: Int,
        val hasMore: Boolean,
    )
    private data class FakePlaylist(val id: String, val title: String, val providerId: String)
    private enum class FakeCategory { Mine, Other }
    private data class FakeVideo(val id: String, val title: String, val providerId: String)

    private class FeaturePort : ProviderFeatureDetailPort<FakeFeature, FakeContent, FakeTrack> {
        var played = emptyList<FakeTrack>()

        override suspend fun loadPage(feature: FakeFeature, offset: Int): FakeContent = when (offset) {
            0 -> FakeContent(listOf(FakeTrack("a"), FakeTrack("b")), nextOffset = 2, hasMore = true)
            else -> FakeContent(listOf(FakeTrack("b"), FakeTrack("c")), nextOffset = 4, hasMore = false)
        }

        override fun featureId(feature: FakeFeature) = feature.id
        override fun featureTitle(feature: FakeFeature) = feature.title
        override fun featureProviderId(feature: FakeFeature) = feature.providerId
        override fun isDynamicQueueFeature(feature: FakeFeature) = false
        override fun contentTracks(content: FakeContent) = content.tracks
        override fun contentNextOffset(content: FakeContent) = content.nextOffset
        override fun contentHasMore(content: FakeContent) = content.hasMore
        override fun contentIsLoginRequired(content: FakeContent) = false
        override fun contentErrorMessage(content: FakeContent): String? = null
        override fun contentProviderName(content: FakeContent) = "测试来源"
        override fun contentCount(content: FakeContent) = content.tracks.size
        override fun mergeContent(current: FakeContent?, page: FakeContent): FakeContent {
            if (current == null) return page
            val seen = current.tracks.mapTo(mutableSetOf(), FakeTrack::id)
            return page.copy(tracks = current.tracks + page.tracks.filter { seen.add(it.id) })
        }
        override fun errorMessage(throwable: Throwable, fallback: String, providerId: String?) =
            throwable.message ?: fallback
        override fun open(feature: FakeFeature) = Unit
        override fun close() = Unit
        override fun playFeatureTracks(tracks: List<FakeTrack>, index: Int, feature: FakeFeature) {
            played = tracks
        }
        override fun playTracks(tracks: List<FakeTrack>, index: Int) {
            played = tracks
        }
    }

    private class PlaylistPort : ProviderPlaylistDetailPort<FakePlaylist, FakeCategory, FakeTrack> {
        val mutations = mutableListOf<String>()

        override suspend fun loadPage(
            playlist: FakePlaylist,
            offset: Int,
        ) = ProviderPlaylistDetailPage(
            playlist = playlist,
            tracks = listOf(FakeTrack("t1", "歌曲", playlist.providerId)),
            nextOffset = 1,
            hasMore = false,
        )

        override suspend fun removeTrack(playlist: FakePlaylist, track: FakeTrack) =
            ProviderDetailMutationResult(true, "已移除")
        override suspend fun deletePlaylist(playlist: FakePlaylist) = ProviderDetailMutationResult(true)
        override suspend fun recordPlayback(playlist: FakePlaylist) = Unit
        override fun playlistId(playlist: FakePlaylist) = playlist.id
        override fun playlistTitle(playlist: FakePlaylist) = playlist.title
        override fun playlistProviderId(playlist: FakePlaylist) = playlist.providerId
        override fun trackId(track: FakeTrack) = track.id
        override fun trackTitle(track: FakeTrack) = track.title
        override fun trackBelongsToProvider(track: FakeTrack, providerId: String) = track.source == providerId
        override fun isMinePlaylistCategory(category: FakeCategory?) = category == FakeCategory.Mine
        override fun isLoggedIn(providerId: String) = true
        override fun canRemoveSongFromPlaylist(providerId: String) = true
        override fun canDeletePlaylist(providerId: String) = true
        override fun errorMessage(throwable: Throwable, fallback: String, providerId: String?) =
            throwable.message ?: fallback
        override fun open(playlist: FakePlaylist, category: FakeCategory?) = Unit
        override fun close() = Unit
        override fun playPlaylistTracks(tracks: List<FakeTrack>, index: Int, playlistId: String) = Unit
        override fun playAllPlaylistTracks(tracks: List<FakeTrack>, playlistId: String) = Unit
        override fun appendPlaylistTracks(playlistId: String, tracks: List<FakeTrack>) = Unit
        override fun onProviderMutation(providerId: String) {
            mutations += providerId
        }
    }

    private class VideoPort : ProviderVideoDetailPort<FakeVideo, String> {
        override suspend fun loadPlayback(video: FakeVideo) = ProviderVideoPlaybackResult(video, "stream:${video.id}")
        override fun videoId(video: FakeVideo) = video.id
        override fun videoTitle(video: FakeVideo) = video.title
        override fun videoProviderId(video: FakeVideo) = video.providerId
        override fun errorMessage(throwable: Throwable, fallback: String, providerId: String?) = fallback
        override fun open(video: FakeVideo) = Unit
        override fun close() = Unit
    }
}
