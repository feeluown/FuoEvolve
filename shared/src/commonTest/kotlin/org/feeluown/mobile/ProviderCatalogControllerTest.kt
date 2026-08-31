@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.feeluown.mobile

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderCatalogControllerTest {
    @Test
    fun catalogUiStateKeepsStableDefaultConstructor() {
        val state = ProviderCatalogUiState()

        assertTrue(state.availableProviders.isEmpty())
        assertTrue(state.providers.isEmpty())
        assertTrue(state.features.isEmpty())
        assertTrue(state.capabilities.isEmpty())
        assertEquals(ProviderSessionState(), state.sessions)
        assertEquals(DEFAULT_ENABLED_PROVIDER_IDS, state.enabledProviderIds)
        assertEquals(DEFAULT_PROVIDER_ORDER_IDS, state.providerOrderIds)
        assertTrue(state.searchProviderIds.isEmpty())
        assertTrue(state.recommendProviderIds.isEmpty())
        assertTrue(state.exploreProviderIds.isEmpty())
        assertTrue(state.mineProviderIds.isEmpty())
        assertTrue(state.replacementProviderIds.isEmpty())
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun refreshRehydratesPersistedProviderLoginState() = runTest {
        val providerRepository = PersistedLoginProviderRepository()
        val sessionRepository = DefaultProviderSessionRepository(providerRepository)
        val settingsRepository = InMemoryAppSettingsRepository(
            AppSettings(enabledProviderIds = setOf(PROVIDER.providerId))
        )

        val controller = createProviderCatalogFeatureController(
            providerRegistry = providerRepository,
            providerCatalog = providerRepository,
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

    private class PersistedLoginProviderRepository :
        ProviderRegistryRepository,
        ProviderCatalogRepository,
        ProviderAuthRepository {
        var authStateCalls = 0

        override suspend fun initialize() = Unit
        override suspend fun providers(): List<ProviderInfo> = listOf(PROVIDER)
        override suspend fun features(): List<ProviderFeature> = emptyList()
        override suspend fun loadFeature(feature: ProviderFeature): ProviderContentSection = ProviderContentSection(feature)
        override suspend fun playlistTracks(playlist: ProviderPlaylist): List<MusicTrack> = emptyList()
        override suspend fun mediaItemTracks(item: MediaRef): List<MusicTrack> = emptyList()

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
    }

    private companion object {
        val PROVIDER = ProviderInfo("netease", "网易云音乐")
    }
}
