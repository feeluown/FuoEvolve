package org.feeluown.mobile.feature.providercatalog

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderCatalogFeatureTest {
    @Test
    fun refreshNormalizesEnabledProvidersAndRehydratesSessions() = runTest {
        val repository = FakeRepository()
        val preferences = FakePreferences(
            ProviderCatalogPreferences(enabledProviderIds = setOf("missing")),
        )
        val sessions = FakeSessions()

        val owner = createProviderCatalogFeatureOwner(
            repository = repository,
            preferences = preferences,
            sessions = sessions,
            scope = backgroundScope,
            defaultEnabledProviderIds = setOf("netease"),
            defaultProviderOrderIds = listOf("netease", "qqmusic"),
        )
        runCurrent()

        assertEquals(setOf("netease"), owner.state.value.enabledProviderIds)
        assertEquals(setOf("netease"), repository.enabled)
        assertEquals(listOf("netease", "qqmusic"), owner.state.value.providers.map { it.id })
        assertEquals(listOf("netease", "qqmusic"), sessions.refreshed)
        assertTrue(preferences.state.value.settings.enabledProviderIds == setOf("netease"))
        assertTrue(owner.state.value.isInitialized)
    }

    @Test
    fun initializationCompletionIsPublishedWhenCatalogHasNoFeatures() = runTest {
        val owner = createProviderCatalogFeatureOwner(
            repository = FakeRepository(featureValues = emptyList()),
            preferences = FakePreferences(ProviderCatalogPreferences()),
            sessions = FakeSessions(),
            scope = backgroundScope,
            defaultEnabledProviderIds = setOf("netease"),
        )
        runCurrent()

        assertTrue(owner.state.value.features.isEmpty())
        assertTrue(owner.state.value.isInitialized)
    }

    @Test
    fun initializationCompletionIsPublishedAfterFailure() = runTest {
        val owner = createProviderCatalogFeatureOwner(
            repository = FakeRepository(initializeFailure = IllegalStateException("catalog failed")),
            preferences = FakePreferences(ProviderCatalogPreferences()),
            sessions = FakeSessions(),
            scope = backgroundScope,
        )
        runCurrent()

        assertEquals("catalog failed", owner.state.value.errorMessage)
        assertTrue(owner.state.value.isInitialized)
    }

    @Test
    fun disablingFinalAvailableProviderIsRejected() {
        assertEquals(
            setOf("netease"),
            updatedEnabledProviderIds(
                current = setOf("netease"),
                providerId = "netease",
                enabled = false,
                availableProviderIds = listOf("netease", "qqmusic"),
                defaultEnabledProviderIds = setOf("netease"),
            ),
        )
    }

    private data class Provider(val id: String, val name: String)
    private data class Capability(val providerId: String)

    private class FakeRepository(
        private val featureValues: List<String> = listOf("recommend"),
        private val initializeFailure: Throwable? = null,
    ) : ProviderCatalogRepositoryPort<Provider, String, Capability> {
        var enabled: Set<String> = emptySet()

        override suspend fun initialize() {
            initializeFailure?.let { throw it }
        }

        override suspend fun availableProviders(): List<Provider> = listOf(
            Provider("netease", "NetEase"),
            Provider("qqmusic", "QQ Music"),
        )
        override suspend fun providers(): List<Provider> = availableProviders()
        override suspend fun features(): List<String> = featureValues
        override suspend fun capabilities(): List<Capability> = listOf(Capability("netease"))
        override suspend fun updateEnabledProviders(providerIds: Set<String>) {
            enabled = providerIds
        }
        override fun providerId(provider: Provider): String = provider.id
        override fun providerName(provider: Provider): String = provider.name
        override fun capabilityProviderId(capability: Capability): String = capability.providerId
    }

    private class FakePreferences(initial: ProviderCatalogPreferences) : ProviderCatalogPreferencesPort {
        private val mutableState = MutableStateFlow(
            ProviderCatalogPreferencesState(isLoaded = true, settings = initial),
        )
        override val state: StateFlow<ProviderCatalogPreferencesState> = mutableState

        override suspend fun awaitPreferences(): ProviderCatalogPreferences = mutableState.value.settings

        override suspend fun update(transform: (ProviderCatalogPreferences) -> ProviderCatalogPreferences) {
            mutableState.value = mutableState.value.copy(settings = transform(mutableState.value.settings))
        }
    }

    private class FakeSessions : ProviderCatalogSessionPort<Provider, String> {
        override val state: StateFlow<String> = MutableStateFlow("ready")
        val refreshed = mutableListOf<String>()

        override suspend fun updateProviders(providers: List<Provider>) = Unit

        override suspend fun refresh(providerId: String) {
            refreshed += providerId
        }
    }
}
