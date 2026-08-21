package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class ProviderCatalogUiState(
    val availableProviders: List<ProviderInfo> = emptyList(),
    val providers: List<ProviderInfo> = emptyList(),
    val features: List<ProviderFeature> = emptyList(),
    val capabilities: Map<String, ProviderCapabilities> = emptyMap(),
    val sessions: ProviderSessionState = ProviderSessionState(),
    val enabledProviderIds: Set<String> = DEFAULT_ENABLED_PROVIDER_IDS,
    val providerOrderIds: List<String> = DEFAULT_PROVIDER_ORDER_IDS,
    val searchProviderIds: Set<String> = emptySet(),
    val recommendProviderIds: Set<String> = emptySet(),
    val exploreProviderIds: Set<String> = emptySet(),
    val mineProviderIds: Set<String> = emptySet(),
    val replacementProviderIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

interface ProviderCatalogFeatureController {
    val uiState: StateFlow<ProviderCatalogUiState>
    fun refresh()
    fun setProviderEnabled(providerId: String, enabled: Boolean)
    fun moveProvider(providerId: String, offset: Int)
    fun setDisplayProviderEnabled(section: ProviderDisplaySection, providerId: String, enabled: Boolean)
}

fun createProviderCatalogFeatureController(
    providerRepository: ProviderMusicRepository,
    sessionRepository: ProviderSessionRepository,
    settingsRepository: AppSettingsRepository,
    scope: CoroutineScope,
): ProviderCatalogFeatureController = DefaultProviderCatalogFeatureController(
    providerRepository,
    sessionRepository,
    settingsRepository,
    scope,
)

private class DefaultProviderCatalogFeatureController(
    private val providerRepository: ProviderMusicRepository,
    private val sessionRepository: ProviderSessionRepository,
    private val settingsRepository: AppSettingsRepository,
    private val scope: CoroutineScope,
) : ProviderCatalogFeatureController {
    private val mutableUiState = MutableStateFlow(ProviderCatalogUiState())
    override val uiState: StateFlow<ProviderCatalogUiState> = mutableUiState.asStateFlow()

    private var catalogAvailable: List<ProviderInfo> = emptyList()
    private var catalogProviders: List<ProviderInfo> = emptyList()
    private var catalogFeatures: List<ProviderFeature> = emptyList()
    private var catalogCapabilities: Map<String, ProviderCapabilities> = emptyMap()
    private var loading = false
    private var error: String? = null

    init {
        scope.launch {
            combine(settingsRepository.state, sessionRepository.state) { settings, sessions -> settings to sessions }
                .collect { publish() }
        }
        refresh()
    }

    override fun refresh() {
        scope.launch {
            loading = true
            error = null
            publish()
            runCatching {
                providerRepository.initialize()
                val settings = settingsRepository.awaitSettings()
                catalogAvailable = providerRepository.availableProviders()
                val availableIds = catalogAvailable.map(ProviderInfo::providerId)
                val enabled = normalizedEnabledProviderIds(settings.enabledProviderIds, availableIds)
                if (settings.enabledProviderIds != enabled) {
                    settingsRepository.update { current -> current.copy(enabledProviderIds = enabled) }
                }
                providerRepository.updateEnabledProviders(enabled)
                catalogProviders = providerRepository.providers().sortedWith(providerComparator(settings.providerOrderIds))
                sessionRepository.updateProviders(catalogProviders)
                refreshProviderSessions(catalogProviders)
                catalogCapabilities = providerRepository.providerCapabilities().associateBy { it.providerId }
                catalogFeatures = providerRepository.features()
            }.onFailure { throwable ->
                error = throwable.message ?: throwable::class.simpleName
            }
            loading = false
            publish()
        }
    }

    override fun setProviderEnabled(providerId: String, enabled: Boolean) {
        scope.launch {
            settingsRepository.update { settings ->
                val availableIds = catalogAvailable.map(ProviderInfo::providerId)
                val next = updatedEnabledProviderIds(
                    current = settings.enabledProviderIds,
                    providerId = providerId,
                    enabled = enabled,
                    availableProviderIds = availableIds,
                )
                if (next == settings.enabledProviderIds) settings else settings.copy(enabledProviderIds = next)
            }
            refresh()
        }
    }

    override fun moveProvider(providerId: String, offset: Int) {
        scope.launch {
            settingsRepository.update { settings ->
                val available = catalogAvailable.map(ProviderInfo::providerId)
                val order = (settings.providerOrderIds + available).distinct().toMutableList()
                val from = order.indexOf(providerId)
                if (from < 0) return@update settings
                val to = (from + offset).coerceIn(0, order.lastIndex)
                if (from == to) return@update settings
                order.removeAt(from)
                order.add(to, providerId)
                settings.copy(providerOrderIds = order)
            }
            publish()
        }
    }

    override fun setDisplayProviderEnabled(
        section: ProviderDisplaySection,
        providerId: String,
        enabled: Boolean,
    ) {
        scope.launch {
            settingsRepository.update { settings ->
                fun next(current: Set<String>): Set<String> = if (enabled) current + providerId else current - providerId
                when (section) {
                    ProviderDisplaySection.Search -> settings.copy(searchProviderIds = next(settings.searchProviderIds))
                    ProviderDisplaySection.Recommend -> settings.copy(recommendProviderIds = next(settings.recommendProviderIds))
                    ProviderDisplaySection.Explore -> settings.copy(exploreProviderIds = next(settings.exploreProviderIds))
                    ProviderDisplaySection.Mine -> settings.copy(mineProviderIds = next(settings.mineProviderIds))
                    ProviderDisplaySection.Replace -> settings.copy(
                        smartReplacementProviderIds = next(settings.smartReplacementProviderIds),
                    )
                }
            }
        }
    }

    private suspend fun refreshProviderSessions(providers: List<ProviderInfo>) {
        providers.forEach { provider ->
            // Rehydrate the provider-owned persisted login state after rebuilding the catalog.
            // A failed auth probe should be scoped to that provider and must not make catalog
            // initialization fail for every source.
            runCatching { sessionRepository.refresh(provider.providerId) }
        }
    }

    private fun publish() {
        val settingsState = settingsRepository.state.value
        val settings = settingsState.settings
        val availableIds = catalogAvailable.map(ProviderInfo::providerId)
        val enabled = normalizedEnabledProviderIds(settings.enabledProviderIds, availableIds)
        val ordered = catalogProviders.sortedWith(providerComparator(settings.providerOrderIds))
        mutableUiState.value = ProviderCatalogUiState(
            availableProviders = catalogAvailable,
            providers = ordered,
            features = catalogFeatures,
            capabilities = catalogCapabilities,
            sessions = sessionRepository.state.value,
            enabledProviderIds = enabled,
            providerOrderIds = settings.providerOrderIds,
            searchProviderIds = settings.searchProviderIds,
            recommendProviderIds = settings.recommendProviderIds,
            exploreProviderIds = settings.exploreProviderIds,
            mineProviderIds = settings.mineProviderIds,
            replacementProviderIds = settings.smartReplacementProviderIds,
            isLoading = loading || !settingsState.isLoaded,
            errorMessage = error ?: settingsState.errorMessage,
        )
    }

    private fun providerComparator(orderIds: List<String>): Comparator<ProviderInfo> {
        val order = orderIds.withIndex().associate { (index, id) -> id to index }
        return compareBy<ProviderInfo> { order[it.providerId] ?: Int.MAX_VALUE }
            .thenBy(ProviderInfo::providerName)
    }
}

/**
 * Resolves the persisted provider selection against the currently available catalog.
 * At least one available provider is always retained so UI state and repository state cannot
 * disagree after a user disables providers or an installed provider disappears.
 */
internal fun normalizedEnabledProviderIds(
    configuredProviderIds: Set<String>,
    availableProviderIds: Collection<String>,
): Set<String> {
    val available = availableProviderIds.distinct()
    if (available.isEmpty()) return emptySet()
    val availableSet = available.toSet()
    val configured = configuredProviderIds.filterTo(linkedSetOf(), availableSet::contains)
    if (configured.isNotEmpty()) return configured
    val defaults = DEFAULT_ENABLED_PROVIDER_IDS.filterTo(linkedSetOf(), availableSet::contains)
    return defaults.ifEmpty { linkedSetOf(available.first()) }
}

/** Applies an enable/disable request while protecting the final available provider. */
internal fun updatedEnabledProviderIds(
    current: Set<String>,
    providerId: String,
    enabled: Boolean,
    availableProviderIds: Collection<String>,
): Set<String> {
    val available = availableProviderIds.distinct()
    if (available.isEmpty()) {
        if (enabled) return current + providerId
        return if (providerId in current && current.size <= 1) current else current - providerId
    }
    val normalized = normalizedEnabledProviderIds(current, available)
    if (providerId !in available) return normalized
    if (enabled) return normalized + providerId
    if (providerId !in normalized || normalized.size <= 1) return normalized
    return normalized - providerId
}
