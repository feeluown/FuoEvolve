package org.feeluown.mobile.provider.qqmusic

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.feeluown.mobile.MediaRef
import org.feeluown.mobile.MediaRefType
import org.feeluown.mobile.MusicTrack
import org.feeluown.mobile.ProviderAuthState
import org.feeluown.mobile.ProviderContentSection
import org.feeluown.mobile.ProviderContentType
import org.feeluown.mobile.ProviderFailure
import org.feeluown.mobile.ProviderFailureKind
import org.feeluown.mobile.ProviderFeature
import org.feeluown.mobile.ProviderFeatureCategory
import org.feeluown.mobile.ProviderFeatureFilterCodec
import org.feeluown.mobile.ProviderMediaItemDetail
import org.feeluown.mobile.ProviderPlaylist
import org.feeluown.mobile.ProviderSearchResults
import org.feeluown.mobile.provider.core.KotlinMusicProvider
import org.feeluown.mobile.provider.core.ProviderCredentialStore
import org.feeluown.mobile.provider.core.ProviderCredentials
import org.feeluown.mobile.provider.core.ProviderRuntimeDependencies
import org.feeluown.mobile.provider.core.ProviderSessionCredential
import org.feeluown.mobile.provider.core.network.ProviderHttpClient
import org.feeluown.mobile.provider.core.network.currentTimeMillis
import org.feeluown.mobile.providerFailureOrNull

/**
 * QQ Music module-local content/library composition.
 *
 * The delegates are injectable so the factory can share one core provider instance across
 * playback, content, account and library capabilities instead of constructing nested providers.
 */
internal class QQMusicCompositeProvider(
    dependencies: ProviderRuntimeDependencies,
    private val content: QQMusicContentProvider = QQMusicContentProvider(
        dependencies.http,
        dependencies.credentials,
    ),
    private val userLibrary: QQMusicUserLibrary = QQMusicUserLibrary(
        dependencies.http,
        dependencies.credentials,
    ),
    private val followedArtists: QQMusicFollowedArtistsLoader = QQMusicFollowedArtistsLoader(
        dependencies.http,
        dependencies.credentials,
    ),
    private val artistDetails: QQMusicArtistDetailProvider = QQMusicArtistDetailProvider(
        dependencies.http,
        dependencies.credentials,
    ),
    private val sessionValidator: QQMusicSessionValidator = QQMusicSessionValidator(
        dependencies.http,
        dependencies.credentials,
    ),
    private val http: ProviderHttpClient = dependencies.http,
    private val credentials: ProviderCredentialStore = dependencies.credentials,
    /** Injectable clock so session expiry/renewal decisions stay testable. */
    private val nowMillis: () -> Long = { currentTimeMillis() },
) : KotlinMusicProvider {
    /** Serializes renewals so a burst of account refreshes exchanges the same key only once. */
    private val renewalMutex = Mutex()

    override val id: String get() = content.id
    override val name: String get() = content.name
    override val info get() = content.info
    override val capabilities get() = content.capabilities.copy(
        canFavoritePlaylist = true,
        canUnfavoritePlaylist = true,
        canFavoriteArtist = true,
        canUnfavoriteArtist = true,
        canFavoriteAlbum = true,
        canUnfavoriteAlbum = true,
    )
    override val features: List<ProviderFeature> = (content.features + MINE_FEATURES).distinctBy { it.id }

    override suspend fun initialize() = content.initialize()

    override suspend fun search(keyword: String): ProviderSearchResults =
        content.search(keyword).let(::normalizeSearchArtists)

    override suspend fun trackDetail(identifier: String) = content.trackDetail(identifier)
    override suspend fun resolve(track: MusicTrack, qualityPolicy: String) = content.resolve(track, qualityPolicy)
    override suspend fun lyrics(track: MusicTrack) = content.lyrics(track)
    override suspend fun lyricsSearchKeyword(track: MusicTrack) = content.lyricsSearchKeyword(track)

    override suspend fun authState(): ProviderAuthState = enrichAuth(expiryAwareAuthState(content.authState()))

    /**
     * Answers "is the stored session still usable?" without an unnecessary network round trip.
     *
     * A renewable session (obtained from the login authorization code) is kept alive by exchanging
     * its `refresh_token` for a fresh `musickey` once the current key is past half of its lifetime;
     * that is the only way a QQ Music login survives longer than the 3 day cookie/key lifetime.
     * Legacy cookie-only logins have no renewable material and keep using the cached server probe.
     */
    override suspend fun verifySession(): ProviderAuthState {
        val stored = credentials.read(ID)
        val session = stored?.session
        if (stored != null && session != null && session.hasAuthKey) {
            return verifyRenewableSession(stored, session)
        }
        val base = enrichAuth(content.authState())
        if (!base.isLoggedIn) return base
        // The validator fingerprints the stored cookies, so a fresh login or a credential restore
        // re-probes automatically while repeated refreshes reuse the cached verdict.
        if (sessionValidator.isSessionAlive() != false) return base
        return expiredSessionState(base)
    }

    private suspend fun verifyRenewableSession(
        stored: ProviderCredentials,
        session: ProviderSessionCredential,
    ): ProviderAuthState {
        val now = nowMillis()
        if (session.isGrantExpiredAt(now)) {
            return expiredSessionState(content.authState(), GRANT_EXPIRED_MESSAGE)
        }
        if (session.shouldRenewAt(now)) {
            val renewed = try {
                renewStoredSession(stored, session)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                return expiredSessionState(content.authState(), renewalFailureMessage(failure))
            }
            if (renewed.isGrantExpiredAt(nowMillis())) {
                return expiredSessionState(content.authState(), GRANT_EXPIRED_MESSAGE)
            }
            return enrichAuth(content.authState())
        }
        if (session.isAuthKeyExpiredAt(now) && !session.canRenewAt(now)) {
            return expiredSessionState(content.authState(), AUTH_KEY_EXPIRED_MESSAGE)
        }
        return enrichAuth(content.authState())
    }

    /**
     * Exchanges the stored session for a fresh auth key and persists both the session and the
     * cookies synthesized from it, so every other QQ Music request path sees the new key.
     *
     * @throws Throwable when the exchange fails; the caller turns that into a LoginExpired state
     *   while the previous credentials stay on disk for a later retry.
     */
    private suspend fun renewStoredSession(
        stored: ProviderCredentials,
        session: ProviderSessionCredential,
    ): ProviderSessionCredential = renewalMutex.withLock {
        // Another caller may have renewed while this one waited for the lock; reuse its key instead
        // of exhausting the grant with a burst of exchanges.
        val latest = credentials.read(ID)
        val latestSession = latest?.session
        if (
            latestSession != null &&
            latestSession.hasAuthKey &&
            latestSession.authKey != session.authKey &&
            !latestSession.shouldRenewAt(nowMillis())
        ) {
            return@withLock latestSession
        }
        val renewed = renewSession(http, session, nowMillis())
        val base = latest ?: stored
        credentials.write(
            ID,
            base.copy(
                cookies = base.cookies.filterKeys { key -> key !in SESSION_COOKIE_KEYS } +
                    qqSessionCookies(renewed),
                // Session cookies are authoritative; a stale captured header must not shadow them.
                cookieHeader = null,
                session = renewed,
            ),
        )
        renewed
    }

    /**
     * Once this process knows the stored session is dead, keep reporting it as dead from [authState]
     * too: [verifySession] performs the expensive work, this only replays the local verdict so the
     * account card stops claiming 已登录 before the next catalogue refresh.
     *
     * A renewable session whose key already expired is still logged in *locally*: the very next
     * [verifySession] (or account refresh) renews it before any account request is made.
     */
    private suspend fun expiryAwareAuthState(base: ProviderAuthState): ProviderAuthState {
        if (!base.isLoggedIn) return base
        val session = credentials.read(ID)?.session
        if (session != null && session.hasAuthKey) {
            val now = nowMillis()
            if (session.isGrantExpiredAt(now)) return expiredSessionState(base, GRANT_EXPIRED_MESSAGE)
            if (session.isAuthKeyExpiredAt(now) && !session.canRenewAt(now)) {
                return expiredSessionState(base, AUTH_KEY_EXPIRED_MESSAGE)
            }
            return base
        }
        if (sessionValidator.cachedVerdict() != false) return base
        return expiredSessionState(base)
    }

    private fun expiredSessionState(
        base: ProviderAuthState,
        technicalMessage: String = PROBE_EXPIRED_MESSAGE,
    ): ProviderAuthState = base.copy(
        isLoggedIn = false,
        userName = null,
        failure = ProviderFailure(
            kind = ProviderFailureKind.LoginExpired,
            providerId = ID,
            technicalMessage = technicalMessage,
        ),
    )

    /**
     * Completes the browser login by exchanging the single-use OAuth code captured from QQ Music's
     * redirect for a renewable session, then synthesizes the cookies every request path expects.
     *
     * A failed exchange reports the mapped failure in the returned state instead of throwing, which
     * is how the rest of this provider surfaces account problems, and persists nothing.
     */
    override suspend fun loginWithAuthorizationCode(code: String, loginType: Int?): ProviderAuthState {
        val result = try {
            exchangeAuthorizationCode(http, code, loginType, nowMillis())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            return failedAuthState(failure)
        }
        val stored = credentials.read(ID)
        credentials.write(
            ID,
            ProviderCredentials(
                cookies = stored?.cookies.orEmpty().filterKeys { key -> key !in SESSION_COOKIE_KEYS } +
                    result.cookies(),
                session = result.session,
            ),
        )
        return enrichAuth(content.authState())
    }

    private fun failedAuthState(failure: Throwable): ProviderAuthState = ProviderAuthState(
        providerId = ID,
        providerName = name,
        isLoggedIn = false,
        userName = null,
        failure = failure.providerFailureOrNull(ID) ?: ProviderFailure(
            kind = ProviderFailureKind.Unknown,
            providerId = ID,
            technicalMessage = failure.message,
        ),
    )

    private fun renewalFailureMessage(failure: Throwable): String {
        val detail = failure.message?.takeIf(String::isNotBlank)
        return if (detail == null) RENEWAL_FAILED_MESSAGE else "$RENEWAL_FAILED_MESSAGE: $detail"
    }

    override suspend fun loginWithCookies(cookiesJson: String): ProviderAuthState =
        enrichAuth(content.loginWithCookies(cookiesJson))

    override suspend fun loginWithHeaders(authorization: String, cookie: String): ProviderAuthState =
        enrichAuth(content.loginWithHeaders(authorization, cookie))

    override suspend fun loginWithHeaderFile(headerFileJson: String): ProviderAuthState =
        content.loginWithHeaderFile(headerFileJson)

    override suspend fun loginWithOAuth(
        accessToken: String,
        refreshToken: String,
        expiresAtMillis: Long?,
        scope: String?,
        clientId: String,
        clientSecret: String,
    ): ProviderAuthState = content.loginWithOAuth(
        accessToken,
        refreshToken,
        expiresAtMillis,
        scope,
        clientId,
        clientSecret,
    )

    override suspend fun logout(): ProviderAuthState = content.logout()

    override suspend fun loadFeature(
        feature: ProviderFeature,
        offset: Int,
        limit: Int,
    ): ProviderContentSection {
        val featureId = ProviderFeatureFilterCodec.requestId(feature.id)
        val section = if (featureId in USER_LIBRARY_FEATURE_IDS) {
            if (!content.authState().isLoggedIn) {
                ProviderContentSection(feature = feature, isLoginRequired = true)
            } else {
                when (featureId) {
                    USER_PLAYLISTS_FEATURE_ID -> userLibrary.loadPlaylists(feature, offset, limit)
                    FAVORITE_PLAYLISTS_FEATURE_ID -> userLibrary.loadFavoritePlaylists(feature, offset, limit)
                    FOLLOWED_ARTISTS_FEATURE_ID -> followedArtists.load(feature, offset, limit)
                    FAVORITE_ALBUMS_FEATURE_ID -> userLibrary.loadFavoriteAlbums(feature, offset, limit)
                    else -> ProviderContentSection(feature = feature)
                }
            }
        } else {
            content.loadFeature(feature, offset, limit)
        }
        return normalizeArtists(normalizePlaylists(section, featureId))
    }

    override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> =
        content.playlistTracks(playlist)

    override suspend fun playlistDetail(playlist: ProviderPlaylist, offset: Int, limit: Int) =
        content.playlistDetail(playlist, offset, limit)

    override suspend fun playlistOperationTargets(track: MusicTrack) = content.playlistOperationTargets(track)
    override suspend fun addTrackToPlaylist(playlist: ProviderPlaylist, track: MusicTrack) =
        content.addTrackToPlaylist(playlist, track)
    override suspend fun removeTrackFromPlaylist(playlist: ProviderPlaylist, track: MusicTrack) =
        content.removeTrackFromPlaylist(playlist, track)
    override suspend fun createPlaylist(name: String) = content.createPlaylist(name)
    override suspend fun deletePlaylist(playlist: ProviderPlaylist) = content.deletePlaylist(playlist)
    override suspend fun setSongDisliked(track: MusicTrack, disliked: Boolean) = content.setSongDisliked(track, disliked)

    override suspend fun mediaItemTracks(item: MediaRef): List<MusicTrack> =
        if (item.providerId == ID && item.type == MediaRefType.Artist) {
            artistDetails.loadTracksPage(
                item = artistDetails.normalizeArtist(item),
                offset = 0,
                limit = MAX_ARTIST_TRACKS,
            ).tracks
        } else {
            content.mediaItemTracks(item)
        }

    override suspend fun mediaItemDetail(
        item: MediaRef,
        tracksOffset: Int,
        albumsOffset: Int,
        limit: Int,
    ): ProviderMediaItemDetail {
        if (item.providerId != ID || item.type != MediaRefType.Artist) {
            return content.mediaItemDetail(item, tracksOffset, albumsOffset, limit)
        }
        val normalized = artistDetails.normalizeArtist(item)
        val base = runCatching {
            content.mediaItemDetail(normalized, tracksOffset, albumsOffset, limit)
        }.getOrNull()
        val trackPage = artistDetails.loadTracksPage(normalized, tracksOffset, limit)
        val baseItem = base?.item
        val mergedItem = (baseItem ?: trackPage.item).copy(
            id = trackPage.item.id,
            coverUrl = trackPage.item.coverUrl ?: baseItem?.coverUrl,
            providerUrl = trackPage.item.providerUrl,
            trackCount = trackPage.total ?: baseItem?.trackCount ?: trackPage.item.trackCount,
        )
        return ProviderMediaItemDetail(
            item = mergedItem,
            tracks = trackPage.tracks,
            albums = base?.albums.orEmpty(),
            tracksNextOffset = trackPage.nextOffset,
            tracksHasMore = trackPage.hasMore,
            albumsNextOffset = base?.albumsNextOffset ?: albumsOffset,
            albumsHasMore = base?.albumsHasMore ?: false,
        )
    }

    override suspend fun similarTracks(track: MusicTrack) = content.similarTracks(track)
    override suspend fun hotComments(track: MusicTrack) = content.hotComments(track)
    override suspend fun trackVideo(track: MusicTrack) = content.trackVideo(track)
    override suspend fun videoPlaybackPayload(video: org.feeluown.mobile.ProviderVideo) = content.videoPlaybackPayload(video)
    override suspend fun resourceState(resourceType: String, resourceId: String) =
        userLibrary.resourceState(resourceType, resourceId)
    override suspend fun setResourceFavorite(resourceType: String, resourceId: String, favorite: Boolean) =
        userLibrary.setResourceFavorite(resourceType, resourceId, favorite)

    private suspend fun enrichAuth(base: ProviderAuthState): ProviderAuthState {
        if (!base.isLoggedIn) return base
        val userName = runCatching { userLibrary.userName() }.getOrNull()
            ?.takeIf(String::isNotBlank)
            ?: return base
        return base.copy(userName = userName)
    }

    private fun normalizeSearchArtists(results: ProviderSearchResults): ProviderSearchResults =
        results.copy(
            artists = results.artists.map { item ->
                if (item.providerId == ID && item.type == MediaRefType.Artist) artistDetails.normalizeArtist(item) else item
            },
        )

    private fun normalizeArtists(section: ProviderContentSection): ProviderContentSection =
        section.copy(
            mediaItems = section.mediaItems.map { item ->
                if (item.providerId == ID && item.type == MediaRefType.Artist) artistDetails.normalizeArtist(item) else item
            },
        )

    private fun normalizePlaylists(section: ProviderContentSection, featureId: String): ProviderContentSection {
        val playlists = when (featureId) {
            USER_PLAYLISTS_FEATURE_ID -> section.playlists.map { playlist ->
                val isFavoriteSongs = playlist.title == FAVORITE_SONGS_TITLE
                playlist.copy(
                    isOwnedByCurrentUser = true,
                    isSubscribed = false,
                    canAddTracks = true,
                    canRemoveTracks = true,
                    canDelete = !isFavoriteSongs,
                )
            }
            FAVORITE_PLAYLISTS_FEATURE_ID -> section.playlists.map { playlist ->
                playlist.copy(
                    isOwnedByCurrentUser = false,
                    isSubscribed = true,
                    canAddTracks = false,
                    canRemoveTracks = false,
                    canDelete = false,
                )
            }
            else -> section.playlists
        }
        return section.copy(playlists = playlists)
    }

    private companion object {
        const val ID = "qqmusic"

        /**
         * Cookies that belong to one specific QQ Music login. A fresh authorization-code login
         * replaces them instead of keeping a previous account's identity cookies around (the request
         * paths prefer `wxuin`/qqmusic_key over anything else stored).
         */
        val SESSION_COOKIE_KEYS = setOf(
            "uin",
            "wxuin",
            "musicid",
            "str_musicid",
            "qm_keyst",
            "qqmusic_key",
            "encryptUin",
            "encrypt_uin",
            "login_type",
            "wxopenid",
            "wxunionid",
            "p_skey",
            "skey",
            "p_lskey",
            "lskey",
        )

        const val PROBE_EXPIRED_MESSAGE = "QQ Music session expired (music.UserInfo.userInfoServer code=1000)"
        const val GRANT_EXPIRED_MESSAGE = "QQ Music session grant expired; sign in again to renew it"
        const val AUTH_KEY_EXPIRED_MESSAGE = "QQ Music session key expired and cannot be renewed"
        const val RENEWAL_FAILED_MESSAGE = "QQ Music session renewal failed"

        const val USER_PLAYLISTS_FEATURE_ID = "qqmusic_user_playlists"
        const val FAVORITE_PLAYLISTS_FEATURE_ID = "qqmusic_favorite_playlists"
        const val FOLLOWED_ARTISTS_FEATURE_ID = "qqmusic_followed_artists"
        const val FAVORITE_ALBUMS_FEATURE_ID = "qqmusic_favorite_albums"
        const val FAVORITE_SONGS_TITLE = "我喜欢"
        const val MAX_ARTIST_TRACKS = 300

        val USER_LIBRARY_FEATURE_IDS = setOf(
            USER_PLAYLISTS_FEATURE_ID,
            FAVORITE_PLAYLISTS_FEATURE_ID,
            FOLLOWED_ARTISTS_FEATURE_ID,
            FAVORITE_ALBUMS_FEATURE_ID,
        )

        val MINE_FEATURES = listOf(
            ProviderFeature(
                FAVORITE_PLAYLISTS_FEATURE_ID,
                ID,
                "QQ 音乐",
                "收藏歌单",
                ProviderFeatureCategory.MineFavoritePlaylists,
                ProviderContentType.Playlists,
                true,
            ),
            ProviderFeature(
                FOLLOWED_ARTISTS_FEATURE_ID,
                ID,
                "QQ 音乐",
                "关注歌手",
                ProviderFeatureCategory.Mine,
                ProviderContentType.Artists,
                true,
            ),
            ProviderFeature(
                FAVORITE_ALBUMS_FEATURE_ID,
                ID,
                "QQ 音乐",
                "收藏专辑",
                ProviderFeatureCategory.Mine,
                ProviderContentType.Albums,
                true,
            ),
        )
    }
}
