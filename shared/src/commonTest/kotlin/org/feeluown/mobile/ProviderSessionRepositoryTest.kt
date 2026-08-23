package org.feeluown.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProviderSessionRepositoryTest {
    @Test
    fun slowRefreshCannotOverwriteNewLogin() = runTest {
        val provider = RacingProviderRepository()
        val repository = DefaultProviderSessionRepository(provider)
        repository.updateProviders(listOf(PROVIDER))

        assertEquals(listOf(PROVIDER), repository.state.value.providers)

        val refresh = async { repository.refresh(PROVIDER.providerId, refreshUserInfo = true) }
        provider.refreshStarted.await()
        val login = async { repository.loginWithCookies(PROVIDER.providerId, "{}") }
        provider.allowRefreshToFinish.complete(Unit)

        refresh.await()
        login.await()

        assertTrue(repository.state.value.authStates.getValue(PROVIDER.providerId).isLoggedIn)
        assertTrue(repository.state.value.operations.isEmpty())
    }

    @Test
    fun logoutPublishesTheFinalSessionState() = runTest {
        val provider = RacingProviderRepository()
        provider.allowRefreshToFinish.complete(Unit)
        val repository = DefaultProviderSessionRepository(provider)
        repository.updateProviders(listOf(PROVIDER))

        repository.loginWithCookies(PROVIDER.providerId, "{}")
        repository.logout(PROVIDER.providerId)

        assertFalse(repository.state.value.authStates.getValue(PROVIDER.providerId).isLoggedIn)
        assertEquals(ProviderSessionState().errors, repository.state.value.errors)
    }

    @Test
    fun providerNeutralOAuthLoginPersistsLoggedInState() = runTest {
        val provider = RacingProviderRepository()
        provider.allowRefreshToFinish.complete(Unit)
        val repository = DefaultProviderSessionRepository(provider)
        repository.updateProviders(listOf(YTMUSIC))

        val state = repository.loginWithOAuth(
            providerId = YTMUSIC.providerId,
            accessToken = "access",
            refreshToken = "refresh",
            expiresAtMillis = 1_700_000_000_000L,
            scope = "https://www.googleapis.com/auth/youtube",
            clientId = "cid",
            clientSecret = "secret",
        )

        assertTrue(state.isLoggedIn)
        assertTrue(repository.state.value.authStates.getValue(YTMUSIC.providerId).isLoggedIn)
        assertTrue(provider.oauthLoggedIn)
    }

    private class RacingProviderRepository : ProviderMusicRepository {
        var isLoggedIn = false
        var oauthLoggedIn = false
        val refreshStarted = CompletableDeferred<Unit>()
        val allowRefreshToFinish = CompletableDeferred<Unit>()

        override suspend fun initialize() = Unit
        override suspend fun providers(): List<ProviderInfo> = listOf(PROVIDER)
        override suspend fun search(keyword: String, providerId: String?): List<MusicTrack> = emptyList()
        override suspend fun resolve(
            track: MusicTrack,
            unavailablePolicy: UnavailablePlaybackPolicy,
            smartReplacementProviderIds: Set<String>,
            smartReplacementMinScore: Double,
            smartReplacementUseOriginalMetadata: Boolean,
            smartReplacementUseOriginalLyrics: Boolean,
        ): PlaybackPayload = PlaybackPayload(
            url = "",
            title = track.title,
            artists = track.artists,
            album = track.album,
            source = track.source,
        )

        override suspend fun authState(providerId: String): ProviderAuthState = state(providerId, isLoggedIn || oauthLoggedIn)

        override suspend fun refreshAuthState(providerId: String): ProviderAuthState {
            val captured = isLoggedIn || oauthLoggedIn
            refreshStarted.complete(Unit)
            allowRefreshToFinish.await()
            return state(providerId, captured)
        }

        override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState {
            isLoggedIn = true
            return state(providerId, true)
        }

        override suspend fun loginWithOAuth(
            providerId: String,
            accessToken: String,
            refreshToken: String,
            expiresAtMillis: Long?,
            scope: String?,
            clientId: String,
            clientSecret: String,
        ): ProviderAuthState {
            oauthLoggedIn = true
            return state(providerId, true)
        }

        override suspend fun loginWithOAuthJson(
            providerId: String,
            oauthJson: String,
            clientId: String,
            clientSecret: String,
        ): ProviderAuthState {
            oauthLoggedIn = true
            return state(providerId, true)
        }

        override suspend fun logout(providerId: String): ProviderAuthState {
            isLoggedIn = false
            oauthLoggedIn = false
            return state(providerId, false)
        }

        override suspend fun updateAudioQualityPolicies(
            wifiPolicy: AudioQualityPolicy,
            cellularPolicy: AudioQualityPolicy,
        ) = Unit

        override suspend fun features(): List<ProviderFeature> = emptyList()
        override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection =
            ProviderContentSection(feature)

        override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> = emptyList()
        override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> = emptyList()

        private fun state(providerId: String, loggedIn: Boolean) = ProviderAuthState(
            providerId = providerId,
            providerName = if (providerId == YTMUSIC.providerId) YTMUSIC.providerName else PROVIDER.providerName,
            isLoggedIn = loggedIn,
        )
    }

    private companion object {
        val PROVIDER = ProviderInfo("netease", "网易云音乐")
        val YTMUSIC = ProviderInfo("ytmusic", "YouTube Music")
    }
}
