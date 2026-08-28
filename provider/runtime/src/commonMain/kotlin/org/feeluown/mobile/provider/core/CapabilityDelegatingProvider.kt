package org.feeluown.mobile.provider.core

import org.feeluown.mobile.MediaRef
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.PlaybackPayload
import org.feeluown.mobile.ProviderAuthState
import org.feeluown.mobile.ProviderCapabilities
import org.feeluown.mobile.ProviderComment
import org.feeluown.mobile.ProviderContentSection
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderInfo
import org.feeluown.mobile.ProviderMediaItemDetail
import org.feeluown.mobile.ProviderMutationResult
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.ProviderPlaylistDetail
import org.feeluown.mobile.ProviderResourceState
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.ProviderVideo
import org.feeluown.mobile.VideoPlaybackPayload

/**
 * Routes the provider SPI by capability instead of forcing every concrete provider to grow into
 * one implementation class. A provider module can migrate incrementally: capabilities that have
 * not been extracted yet simply keep using [base].
 *
 * [presentation] owns the public metadata/capability/feature surface. This matters for providers
 * that enrich capabilities or register extra feature shelves in a decorator.
 */
class CapabilityDelegatingProvider(
    private val base: KotlinMusicProvider,
    private val presentation: KotlinMusicProvider = base,
    private val account: KotlinMusicProvider = base,
    private val discovery: KotlinMusicProvider = base,
    private val content: KotlinMusicProvider = base,
    private val library: KotlinMusicProvider = base,
    private val playback: KotlinMusicProvider = base,
) : KotlinMusicProvider {
    override val id: String get() = presentation.id
    override val name: String get() = presentation.name
    override val info: ProviderInfo get() = presentation.info
    override val capabilities: ProviderCapabilities get() = presentation.capabilities
    override val features: List<ProviderFeature> get() = presentation.features

    override suspend fun initialize() = base.initialize()

    override suspend fun search(keyword: String): ProviderSearchResults = discovery.search(keyword)

    override suspend fun trackDetail(identifier: String): MusicTrack? = playback.trackDetail(identifier)
    override suspend fun resolve(track: MusicTrack, qualityPolicy: String): PlaybackPayload? =
        playback.resolve(track, qualityPolicy)
    override suspend fun lyrics(track: MusicTrack): String? = playback.lyrics(track)
    override suspend fun lyricsSearchKeyword(track: MusicTrack): String? = playback.lyricsSearchKeyword(track)
    override suspend fun similarTracks(track: MusicTrack): List<MusicTrack> = playback.similarTracks(track)
    override suspend fun hotComments(track: MusicTrack): List<ProviderComment> = playback.hotComments(track)
    override suspend fun trackVideo(track: MusicTrack): ProviderVideo? = playback.trackVideo(track)
    override suspend fun videoPlaybackPayload(video: ProviderVideo): VideoPlaybackPayload =
        playback.videoPlaybackPayload(video)

    override suspend fun authState(): ProviderAuthState = account.authState()
    override suspend fun loginWithCookies(cookiesJson: String): ProviderAuthState = account.loginWithCookies(cookiesJson)
    override suspend fun loginWithHeaders(authorization: String, cookie: String): ProviderAuthState =
        account.loginWithHeaders(authorization, cookie)
    override suspend fun loginWithHeaderFile(headerFileJson: String): ProviderAuthState =
        account.loginWithHeaderFile(headerFileJson)
    override suspend fun loginWithOAuth(
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long?,
        scope: String?,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState = account.loginWithOAuth(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtMillis = expiresAtMillis,
        scope = scope,
        clientId = clientId,
        clientSecret = clientSecret,
    )
    override suspend fun logout(): ProviderAuthState = account.logout()

    override suspend fun loadFeature(feature: ProviderFeature, offset: Int, limit: Int): ProviderContentSection =
        content.loadFeature(feature, offset, limit)
    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> = content.playlistTracks(playlist)
    override suspend fun playlistDetail(
        playlist: ProviderPlaylist,
        offset: Int,
        limit: Int,
    ): ProviderPlaylistDetail = content.playlistDetail(playlist, offset, limit)
    override suspend fun mediaItemTracks(item: MediaRef): List<MusicTrack> = content.mediaItemTracks(item)
    override suspend fun mediaItemDetail(
        item: MediaRef,
        tracksOffset: Int,
        albumsOffset: Int,
        limit: Int,
    ): ProviderMediaItemDetail = content.mediaItemDetail(item, tracksOffset, albumsOffset, limit)

    override suspend fun playlistOperationTargets(track: MusicTrack): List<ProviderPlaylist> =
        library.playlistOperationTargets(track)
    override suspend fun addTrackToPlaylist(
        playlist: ProviderPlaylist,
        track: MusicTrack,
    ): ProviderMutationResult = library.addTrackToPlaylist(playlist, track)
    override suspend fun removeTrackFromPlaylist(
        playlist: ProviderPlaylist,
        track: MusicTrack,
    ): ProviderMutationResult = library.removeTrackFromPlaylist(playlist, track)
    override suspend fun createPlaylist(name: String): ProviderMutationResult = library.createPlaylist(name)
    override suspend fun deletePlaylist(playlist: ProviderPlaylist): ProviderMutationResult = library.deletePlaylist(playlist)
    override suspend fun setSongDisliked(track: MusicTrack, disliked: Boolean): ProviderMutationResult =
        library.setSongDisliked(track, disliked)
    override suspend fun resourceState(resourceType: String, resourceId: String): ProviderResourceState =
        library.resourceState(resourceType, resourceId)
    override suspend fun setResourceFavorite(
        resourceType: String,
        resourceId: String,
        favorite: Boolean,
    ): ProviderMutationResult = library.setResourceFavorite(resourceType, resourceId, favorite)
}
