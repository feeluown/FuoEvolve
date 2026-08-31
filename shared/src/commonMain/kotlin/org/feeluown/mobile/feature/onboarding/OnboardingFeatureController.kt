@file:OptIn(kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi::class)

package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import org.feeluown.mobile.feature.onboarding.OnboardingFeatureOwner as CoreOnboardingOwner
import org.feeluown.mobile.feature.onboarding.OnboardingFeatureState as CoreOnboardingState
import org.feeluown.mobile.feature.onboarding.OnboardingPreferencesPort as CorePreferencesPort
import org.feeluown.mobile.feature.onboarding.OnboardingProviderPreferences as CoreProviderPreferences
import org.feeluown.mobile.feature.onboarding.OnboardingProviderRuntimePort as CoreProviderRuntimePort
import org.feeluown.mobile.feature.onboarding.createOnboardingFeatureOwner

typealias OnboardingUiState = CoreOnboardingState

interface OnboardingFeatureController {
    val uiState: StateFlow<OnboardingUiState>
    fun initialize(catalog: ProviderCatalogUiState)
    fun setProviderSelected(providerId: String, selected: Boolean)
    fun setBilibiliReplacementOnly(enabled: Boolean)
    fun applyProviderSelection(onComplete: (Boolean) -> Unit)
    fun complete()
    fun dismissFeedback(feedback: String)
}

fun createOnboardingFeatureController(
    providerRegistry: ProviderRegistryRepository,
    settingsRepository: AppSettingsRepository,
    providerCatalog: ProviderCatalogFeatureController,
    scope: CoroutineScope,
): OnboardingFeatureController {
    val owner = createOnboardingFeatureOwner(
        preferences = BoundOnboardingPreferencesPort(settingsRepository),
        providerRuntime = BoundOnboardingProviderRuntimePort(providerRegistry, providerCatalog),
        smartReplacePolicy = UnavailablePlaybackPolicy.SmartReplace,
        scope = scope,
    )
    return BoundOnboardingFeatureController(owner, providerCatalog)
}

private class BoundOnboardingFeatureController(
    private val owner: CoreOnboardingOwner,
    private val providerCatalog: ProviderCatalogFeatureController,
) : OnboardingFeatureController {
    override val uiState: StateFlow<OnboardingUiState> = owner.state
    override fun initialize(catalog: ProviderCatalogUiState) = owner.initialize(catalog.availableProviders.map(ProviderInfo::providerId))
    override fun setProviderSelected(providerId: String, selected: Boolean) = owner.setProviderSelected(providerId, selected)
    override fun setBilibiliReplacementOnly(enabled: Boolean) = owner.setBilibiliReplacementOnly(enabled)
    override fun applyProviderSelection(onComplete: (Boolean) -> Unit) {
        val availableProviderIds = providerCatalog.uiState.value.availableProviders.mapTo(mutableSetOf(), ProviderInfo::providerId)
        owner.applyProviderSelection(availableProviderIds, onComplete)
    }
    override fun complete() = owner.complete()
    override fun dismissFeedback(feedback: String) = owner.dismissFeedback(feedback)
}

private typealias BoundProviderPreferences = CoreProviderPreferences<UnavailablePlaybackPolicy>

private class BoundOnboardingPreferencesPort(
    private val repository: AppSettingsRepository,
) : CorePreferencesPort<UnavailablePlaybackPolicy> {
    override val providerPreferences: StateFlow<BoundProviderPreferences> =
        repository.state.mapOnboardingState { it.settings.toOnboardingProviderPreferences() }

    override suspend fun updateProviderPreferences(value: BoundProviderPreferences) {
        repository.update { current ->
            current.copy(
                enabledProviderIds = value.enabledProviderIds,
                searchProviderIds = value.searchProviderIds,
                recommendProviderIds = value.recommendProviderIds,
                exploreProviderIds = value.exploreProviderIds,
                mineProviderIds = value.mineProviderIds,
                smartReplacementProviderIds = value.smartReplacementProviderIds,
                unavailablePlaybackPolicy = value.unavailablePlaybackPolicy,
            )
        }
    }

    override suspend fun markCompleted() { repository.update { it.copy(onboardingCompleted = true) } }
}

private class BoundOnboardingProviderRuntimePort(
    private val providerRegistry: ProviderRegistryRepository,
    private val providerCatalog: ProviderCatalogFeatureController,
) : CoreProviderRuntimePort {
    override suspend fun updateEnabledProviders(providerIds: Set<String>) = providerRegistry.updateEnabledProviders(providerIds)
    override fun refreshCatalog() = providerCatalog.refresh()
}

private fun AppSettings.toOnboardingProviderPreferences(): BoundProviderPreferences = CoreProviderPreferences(
    enabledProviderIds = enabledProviderIds,
    searchProviderIds = searchProviderIds,
    recommendProviderIds = recommendProviderIds,
    exploreProviderIds = exploreProviderIds,
    mineProviderIds = mineProviderIds,
    smartReplacementProviderIds = smartReplacementProviderIds,
    unavailablePlaybackPolicy = unavailablePlaybackPolicy,
)

private class OnboardingMappedStateFlow<Source, Target>(
    private val source: StateFlow<Source>,
    private val transform: (Source) -> Target,
) : StateFlow<Target> {
    override val value: Target get() = transform(source.value)
    override val replayCache: List<Target> get() = listOf(value)
    override suspend fun collect(collector: FlowCollector<Target>): Nothing = source.collect(
        object : FlowCollector<Source> {
            override suspend fun emit(value: Source) { collector.emit(transform(value)) }
        },
    )
}

private fun <Source, Target> StateFlow<Source>.mapOnboardingState(
    transform: (Source) -> Target,
): StateFlow<Target> = OnboardingMappedStateFlow(this, transform)
