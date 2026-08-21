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
                val availableIds = catalogAvailable.mapTo(mutableSetOf()) { it.providerId }
                val enabled = settings.enabledProviderIds.filterTo(linkedSetOf(), availableIds::contains)
                    .ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS.filterTo(linkedSetOf(), availableIds::contains) }
                providerRepository.updateEnabledProviders(enabled)
                catalogProviders = providerRepository.providers().sortedWith(providerComparator(settings.providerOrderIds))
                sessionRepository.updateProviders(catalogProviders)
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
                val next = if (enabled) settings.enabledProviderIds + providerId else settings.enabledProviderIds - providerId
                settings.copy(enabledProviderIds = next)
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

    private fun publish() {
        val settingsState = settingsRepository.state.value
        val settings = settingsState.settings
        val availableIds = catalogAvailable.mapTo(mutableSetOf()) { it.providerId }
        val enabled = settings.enabledProviderIds.filterTo(linkedSetOf(), availableIds::contains)
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
