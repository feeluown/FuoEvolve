package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import org.feeluown.mobile.provider.core.CapabilityDelegatingProvider
import org.feeluown.mobile.provider.core.KotlinMusicProvider

class CapabilityDelegatingProviderTest {
    @Test
    fun routesOperationsToTheirCapabilityDelegates() = kotlinx.coroutines.test.runTest {
        val base = RecordingProvider("base")
        val presentation = RecordingProvider("presentation")
        val account = RecordingProvider("account")
        val discovery = RecordingProvider("discovery")
        val content = RecordingProvider("content")
        val library = RecordingProvider("library")
        val playback = RecordingProvider("playback")
        val provider = CapabilityDelegatingProvider(
            base = base,
            presentation = presentation,
            account = account,
            discovery = discovery,
            content = content,
            library = library,
            playback = playback,
        )
        val track = testTrack()
        val playlist = testPlaylist()
        val feature = testFeature()

        assertEquals(presentation.id, provider.id)
        assertSame(presentation.features, provider.features)

        provider.initialize()
        provider.search("query")
        provider.authState()
        provider.resolve(track, "high")
        provider.loadFeature(feature, 0, 20)
        provider.createPlaylist("new")
        provider.playlistDetail(playlist, 0, 20)

        assertEquals(listOf("initialize"), base.calls)
        assertEquals(listOf("search"), discovery.calls)
        assertEquals(listOf("authState"), account.calls)
        assertEquals(listOf("resolve"), playback.calls)
        assertEquals(listOf("loadFeature", "playlistDetail"), content.calls)
        assertEquals(listOf("createPlaylist"), library.calls)
    }
}

private class RecordingProvider(
    private val marker: String,
) : KotlinMusicProvider {
    val calls = mutableListOf<String>()

    override val id: String = marker
    override val name: String = marker
    override val info: ProviderInfo = ProviderInfo(marker, marker)
    override val capabilities: ProviderCapabilities = ProviderCapabilities(marker, marker)
    override val features: List<ProviderFeature> = listOf(testFeature(marker))

    override suspend fun initialize() {
        calls += "initialize"
    }

    override suspend fun search(keyword: String): ProviderSearchResults {
        calls += "search"
        return ProviderSearchResults()
    }

    override suspend fun trackDetail(identifier: String): MusicTrack? {
        calls += "trackDetail"
        return null
    }

    override suspend fun resolve(track: MusicTrack, qualityPolicy: String): PlaybackPayload? {
        calls += "resolve"
        return null
    }

    override suspend fun authState(): ProviderAuthState {
        calls += "authState"
        return ProviderAuthState(marker, marker, false)
    }

    override suspend fun loginWithCookies(cookiesJson: String): ProviderAuthState {
        calls += "loginWithCookies"
        return ProviderAuthState(marker, marker, true)
    }

    override suspend fun loginWithHeaders(authorization: String, cookie: String): ProviderAuthState {
        calls += "loginWithHeaders"
        return ProviderAuthState(marker, marker, true)
    }

    override suspend fun loginWithHeaderFile(headerFileJson: String): ProviderAuthState {
        calls += "loginWithHeaderFile"
        return ProviderAuthState(marker, marker, true)
    }

    override suspend fun logout(): ProviderAuthState {
        calls += "logout"
        return ProviderAuthState(marker, marker, false)
    }

    override suspend fun loadFeature(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        calls += "loadFeature"
        return ProviderContentSection(feature)
    }

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> {
        calls += "playlistTracks"
        return emptyList()
    }

    override suspend fun playlistDetail(
        playlist: ProviderPlaylist,
        offset: Int,
        limit: Int,
    ): ProviderPlaylistDetail {
        calls += "playlistDetail"
        return ProviderPlaylistDetail(playlist)
    }

    override suspend fun playlistOperationTargets(track: MusicTrack): List<ProviderPlaylist> {
        calls += "playlistOperationTargets"
        return emptyList()
    }

    override suspend fun addTrackToPlaylist(
        playlist: ProviderPlaylist,
        track: MusicTrack,
    ): ProviderMutationResult {
        calls += "addTrackToPlaylist"
        return ProviderMutationResult(success = true, message = "ok")
    }

    override suspend fun removeTrackFromPlaylist(
        playlist: ProviderPlaylist,
        track: MusicTrack,
    ): ProviderMutationResult {
        calls += "removeTrackFromPlaylist"
        return ProviderMutationResult(success = true, message = "ok")
    }

    override suspend fun createPlaylist(name: String): ProviderMutationResult {
        calls += "createPlaylist"
        return ProviderMutationResult(success = true, message = "ok")
    }

    override suspend fun deletePlaylist(playlist: ProviderPlaylist): ProviderMutationResult {
        calls += "deletePlaylist"
        return ProviderMutationResult(success = true, message = "ok")
    }

    override suspend fun setSongDisliked(track: MusicTrack, disliked: Boolean): ProviderMutationResult {
        calls += "setSongDisliked"
        return ProviderMutationResult(success = true, message = "ok")
    }

    override suspend fun mediaItemTracks(item: MediaRef): List<MusicTrack> {
        calls += "mediaItemTracks"
        return emptyList()
    }

    override suspend fun mediaItemDetail(
        item: MediaRef,
        tracksOffset: Int,
        albumsOffset: Int,
        limit: Int,
    ): ProviderMediaItemDetail {
        calls += "mediaItemDetail"
        return ProviderMediaItemDetail(item)
    }

    override suspend fun similarTracks(track: MusicTrack): List<MusicTrack> {
        calls += "similarTracks"
        return emptyList()
    }

    override suspend fun hotComments(track: MusicTrack): List<ProviderComment> {
        calls += "hotComments"
        return emptyList()
    }

    override suspend fun trackVideo(track: MusicTrack): ProviderVideo? {
        calls += "trackVideo"
        return null
    }

    override suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload {
        calls += "videoPlaybackPayload"
        return VideoPlaybackPayload(video)
    }

    override suspend fun resourceState(resourceType: String, resourceId: String): ProviderResourceState {
        calls += "resourceState"
        return ProviderResourceState(marker, resourceId)
    }

    override suspend fun setResourceFavorite(
        resourceType: String,
        resourceId: String,
        favorite: Boolean,
    ): ProviderMutationResult {
        calls += "setResourceFavorite"
        return ProviderMutationResult(success = true, message = "ok")
    }
}

private fun testTrack() = MusicTrack(
    id = "test:track",
    title = "Track",
    artists = "Artist",
    album = "Album",
    source = "test",
    sourceType = TrackSourceType.Provider,
)

private fun testPlaylist() = ProviderPlaylist(
    id = "playlist:test:1",
    title = "Playlist",
    providerId = "test",
    providerName = "Test",
)

private fun testFeature(providerId: String = "test") = ProviderFeature(
    id = "$providerId-feature",
    providerId = providerId,
    providerName = providerId,
    title = "Feature",
    category = ProviderFeatureCategory.Music,
    contentType = ProviderContentType.Songs,
    requiresLogin = false,
)