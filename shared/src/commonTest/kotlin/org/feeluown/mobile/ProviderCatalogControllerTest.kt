package org.feeluown.mobile

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderCatalogControllerTest {
    @Test
    fun refreshRehydratesPersistedProviderLoginState() = runTest {
        val providerRepository = PersistedLoginProviderRepository()
        val sessionRepository = DefaultProviderSessionRepository(providerRepository)
        val settingsRepository = InMemoryAppSettingsRepository(
            AppSettings(enabledProviderIds = setOf(PROVIDER.providerId))
        )

        val controller = createProviderCatalogFeatureController(
            providerRepository = providerRepository,
            sessionRepository = sessionRepository,
            settingsRepository = settingsRepository,
            scope = backgroundScope,
        )
        runCurrent()

        val auth = controller.uiState.value.sessions.authStates.getValue(PROVIDER.providerId)
        assertTrue(auth.isLoggedIn)
        assertEquals("Persisted User", auth.userName)
        assertEquals(1, providerRepository.authStateCalls)
    }

    private class PersistedLoginProviderRepository : ProviderMusicRepository {
        var authStateCalls = 0

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

        override suspend fun authState(providerId: String): ProviderAuthState {
            authStateCalls += 1
            return ProviderAuthState(
                providerId = providerId,
                providerName = PROVIDER.providerName,
                isLoggedIn = true,
                userName = "Persisted User",
            )
        }

        override suspend fun loginWithCookies(providerId: String, cookiesJson: String): ProviderAuthState =
            authState(providerId)

        override suspend fun logout(providerId: String): ProviderAuthState = ProviderAuthState(
            providerId = providerId,
            providerName = PROVIDER.providerName,
            isLoggedIn = false,
        )

        override suspend fun updateAudioQualityPolicies(
            wifiPolicy: AudioQualityPolicy,
            cellularPolicy: AudioQualityPolicy,
        ) = Unit

        override suspend fun features(): List<ProviderFeature> = emptyList()

        override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection =
            ProviderContentSection(feature)

        override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> = emptyList()

        override suspend fun mediaItemTracks(item: ProviderMediaItem): List<MusicTrack> = emptyList()
    }

    private companion object {
        val PROVIDER = ProviderInfo("netease", "网易云音乐")
    }
}
