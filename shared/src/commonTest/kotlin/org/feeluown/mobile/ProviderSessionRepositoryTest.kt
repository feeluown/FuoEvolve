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

    private class RacingProviderRepository : ProviderMusicRepository {
        var isLoggedIn = false
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

        override suspend fun authState(providerId: String): ProviderAuthState = state(isLoggedIn)

        override suspend fun refreshAuthState(providerId: String): ProviderAuthState {
            val captured = isLoggedIn
            refreshStarted.complete(Unit)
            allowRefreshToFinish.await()
            return state(captured)
        }

        override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState {
            isLoggedIn = true
            return state(true)
        }

        override suspend fun logout(providerId: String): ProviderAuthState {
            isLoggedIn = false
            return state(false)
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

        private fun state(loggedIn: Boolean) = ProviderAuthState(
            providerId = PROVIDER.providerId,
            providerName = PROVIDER.providerName,
            isLoggedIn = loggedIn,
        )
    }

    private companion object {
        val PROVIDER = ProviderInfo("netease", "网易云音乐")
    }
}
