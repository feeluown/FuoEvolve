package org.feeluown.mobile.feature.providercatalog

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

enum class ProviderCatalogDisplaySection {
    Search,
    Recommend,
    Explore,
    Mine,
    Replace,
}

data class ProviderCatalogPreferences(
    val enabledProviderIds: Set<String> = emptySet(),
    val providerOrderIds: List<String> = emptyList(),
    val searchProviderIds: Set<String> = emptySet(),
    val recommendProviderIds: Set<String> = emptySet(),
    val exploreProviderIds: Set<String> = emptySet(),
    val mineProviderIds: Set<String> = emptySet(),
    val replacementProviderIds: Set<String> = emptySet(),
)

data class ProviderCatalogPreferencesState(
    val isLoaded: Boolean = false,
    val settings: ProviderCatalogPreferences = ProviderCatalogPreferences(),
    val errorMessage: String? = null,
)

data class ProviderCatalogFeatureState<Provider, Feature, Capability, Session>(
    val sessions: Session,
    val availableProviders: List<Provider> = emptyList(),
    val providers: List<Provider> = emptyList(),
    val features: List<Feature> = emptyList(),
    val capabilities: Map<String, Capability> = emptyMap(),
    val enabledProviderIds: Set<String> = emptySet(),
    val providerOrderIds: List<String> = emptyList(),
    val searchProviderIds: Set<String> = emptySet(),
    val recommendProviderIds: Set<String> = emptySet(),
    val exploreProviderIds: Set<String> = emptySet(),
    val mineProviderIds: Set<String> = emptySet(),
    val replacementProviderIds: Set<String> = emptySet(),
    val isInitialized: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

interface ProviderCatalogRepositoryPort<Provider, Feature, Capability> {
    suspend fun initialize()
    suspend fun availableProviders(): List<Provider>
    suspend fun providers(): List<Provider>
    suspend fun features(): List<Feature>
    suspend fun capabilities(): List<Capability>
    suspend fun updateEnabledProviders(providerIds: Set<String>)
    fun providerId(provider: Provider): String
    fun providerName(provider: Provider): String
    fun capabilityProviderId(capability: Capability): String
}

interface ProviderCatalogPreferencesPort {
    val state: StateFlow<ProviderCatalogPreferencesState>
    suspend fun awaitPreferences(): ProviderCatalogPreferences
    suspend fun update(transform: (ProviderCatalogPreferences) -> ProviderCatalogPreferences)
}

interface ProviderCatalogSessionPort<Provider, Session> {
    val state: StateFlow<Session>
    suspend fun updateProviders(providers: List<Provider>)
    suspend fun refresh(providerId: String)
}

interface ProviderCatalogFeatureOwner<Provider, Feature, Capability, Session> {
    val state: StateFlow<ProviderCatalogFeatureState<Provider, Feature, Capability, Session>>
    fun refresh()
    fun setProviderEnabled(providerId: String, enabled: Boolean)
    fun moveProvider(providerId: String, offset: Int)
    fun setDisplayProviderEnabled(section: ProviderCatalogDisplaySection, providerId: String, enabled: Boolean)
}

fun <Provider, Feature, Capability, Session> createProviderCatalogFeatureOwner(
    repository: ProviderCatalogRepositoryPort<Provider, Feature, Capability>,
    preferences: ProviderCatalogPreferencesPort,
    sessions: ProviderCatalogSessionPort<Provider, Session>,
    scope: CoroutineScope,
    defaultEnabledProviderIds: Set<String> = emptySet(),
    defaultProviderOrderIds: List<String> = emptyList(),
): ProviderCatalogFeatureOwner<Provider, Feature, Capability, Session> = DefaultProviderCatalogFeatureOwner(
    repository = repository,
    preferences = preferences,
    sessions = sessions,
    scope = scope,
    defaultEnabledProviderIds = defaultEnabledProviderIds,
    defaultProviderOrderIds = defaultProviderOrderIds,
)

private class DefaultProviderCatalogFeatureOwner<Provider, Feature, Capability, Session>(
    private val repository: ProviderCatalogRepositoryPort<Provider, Feature, Capability>,
    private val preferences: ProviderCatalogPreferencesPort,
    private val sessions: ProviderCatalogSessionPort<Provider, Session>,
    private val scope: CoroutineScope,
    private val defaultEnabledProviderIds: Set<String>,
    private val defaultProviderOrderIds: List<String>,
) : ProviderCatalogFeatureOwner<Provider, Feature, Capability, Session> {
    private val mutableState = MutableStateFlow(
        ProviderCatalogFeatureState<Provider, Feature, Capability, Session>(sessions = sessions.state.value),
    )
    override val state: StateFlow<ProviderCatalogFeatureState<Provider, Feature, Capability, Session>> =
        mutableState.asStateFlow()

    private var catalogAvailable: List<Provider> = emptyList()
    private var catalogProviders: List<Provider> = emptyList()
    private var catalogFeatures: List<Feature> = emptyList()
    private var catalogCapabilities: Map<String, Capability> = emptyMap()
    private var initialized = false
    private var loading = false
    private var error: String? = null

    init {
        scope.launch {
            combine(preferences.state, sessions.state) { _, _ -> Unit }
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
                repository.initialize()
                val settings = preferences.awaitPreferences()
                catalogAvailable = repository.availableProviders()
                val availableIds = catalogAvailable.map(repository::providerId)
                val enabled = normalizedEnabledProviderIds(
                    configuredProviderIds = settings.enabledProviderIds,
                    availableProviderIds = availableIds,
                    defaultEnabledProviderIds = defaultEnabledProviderIds,
                )
                if (settings.enabledProviderIds != enabled) {
                    preferences.update { current -> current.copy(enabledProviderIds = enabled) }
                }
                repository.updateEnabledProviders(enabled)
                catalogProviders = repository.providers().sortedWith(providerComparator(settings.providerOrderIds))
                sessions.updateProviders(catalogProviders)
                refreshProviderSessions(catalogProviders)
                catalogCapabilities = repository.capabilities().associateBy(repository::capabilityProviderId)
                catalogFeatures = repository.features()
            }.onFailure { throwable ->
                error = throwable.message ?: throwable::class.simpleName
            }
            initialized = true
            loading = false
            publish()
        }
    }

    override fun setProviderEnabled(providerId: String, enabled: Boolean) {
        scope.launch {
            preferences.update { settings ->
                val availableIds = catalogAvailable.map(repository::providerId)
                val next = updatedEnabledProviderIds(
                    current = settings.enabledProviderIds,
                    providerId = providerId,
                    enabled = enabled,
                    availableProviderIds = availableIds,
                    defaultEnabledProviderIds = defaultEnabledProviderIds,
                )
                if (next == settings.enabledProviderIds) settings else settings.copy(enabledProviderIds = next)
            }
            refresh()
        }
    }

    override fun moveProvider(providerId: String, offset: Int) {
        scope.launch {
            preferences.update { settings ->
                val available = catalogAvailable.map(repository::providerId)
                val order = (settings.providerOrderIds + defaultProviderOrderIds + available).distinct().toMutableList()
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
        section: ProviderCatalogDisplaySection,
        providerId: String,
        enabled: Boolean,
    ) {
        scope.launch {
            preferences.update { settings ->
                fun next(current: Set<String>): Set<String> = if (enabled) current + providerId else current - providerId
                when (section) {
                    ProviderCatalogDisplaySection.Search -> settings.copy(searchProviderIds = next(settings.searchProviderIds))
                    ProviderCatalogDisplaySection.Recommend -> settings.copy(recommendProviderIds = next(settings.recommendProviderIds))
                    ProviderCatalogDisplaySection.Explore -> settings.copy(exploreProviderIds = next(settings.exploreProviderIds))
                    ProviderCatalogDisplaySection.Mine -> settings.copy(mineProviderIds = next(settings.mineProviderIds))
                    ProviderCatalogDisplaySection.Replace -> settings.copy(
                        replacementProviderIds = next(settings.replacementProviderIds),
                    )
                }
            }
        }
    }

    private suspend fun refreshProviderSessions(providers: List<Provider>) {
        providers.forEach { provider ->
            runCatching { sessions.refresh(repository.providerId(provider)) }
        }
    }

    private fun publish() {
        val preferencesState = preferences.state.value
        val settings = preferencesState.settings
        val availableIds = catalogAvailable.map(repository::providerId)
        val enabled = normalizedEnabledProviderIds(
            configuredProviderIds = settings.enabledProviderIds,
            availableProviderIds = availableIds,
            defaultEnabledProviderIds = defaultEnabledProviderIds,
        )
        mutableState.value = ProviderCatalogFeatureState(
            sessions = sessions.state.value,
            availableProviders = catalogAvailable,
            providers = catalogProviders.sortedWith(providerComparator(settings.providerOrderIds)),
            features = catalogFeatures,
            capabilities = catalogCapabilities,
            enabledProviderIds = enabled,
            providerOrderIds = settings.providerOrderIds,
            searchProviderIds = settings.searchProviderIds,
            recommendProviderIds = settings.recommendProviderIds,
            exploreProviderIds = settings.exploreProviderIds,
            mineProviderIds = settings.mineProviderIds,
            replacementProviderIds = settings.replacementProviderIds,
            isInitialized = initialized,
            isLoading = loading || !preferencesState.isLoaded,
            errorMessage = error ?: preferencesState.errorMessage,
        )
    }

    private fun providerComparator(orderIds: List<String>): Comparator<Provider> {
        val order = (orderIds + defaultProviderOrderIds).distinct().withIndex().associate { (index, id) -> id to index }
        return compareBy<Provider> { order[repository.providerId(it)] ?: Int.MAX_VALUE }
            .thenBy(repository::providerName)
    }
}

fun normalizedEnabledProviderIds(
    configuredProviderIds: Set<String>,
    availableProviderIds: Collection<String>,
    defaultEnabledProviderIds: Set<String> = emptySet(),
): Set<String> {
    val available = availableProviderIds.distinct()
    if (available.isEmpty()) return emptySet()
    val availableSet = available.toSet()
    val configured = configuredProviderIds.filterTo(linkedSetOf(), availableSet::contains)
    if (configured.isNotEmpty()) return configured
    val defaults = defaultEnabledProviderIds.filterTo(linkedSetOf(), availableSet::contains)
    return defaults.ifEmpty { linkedSetOf(available.first()) }
}

fun updatedEnabledProviderIds(
    current: Set<String>,
    providerId: String,
    enabled: Boolean,
    availableProviderIds: Collection<String>,
    defaultEnabledProviderIds: Set<String> = emptySet(),
): Set<String> {
    val available = availableProviderIds.distinct()
    if (available.isEmpty()) {
        if (enabled) return current + providerId
        return if (providerId in current && current.size <= 1) current else current - providerId
    }
    val normalized = normalizedEnabledProviderIds(current, available, defaultEnabledProviderIds)
    if (providerId !in available) return normalized
    if (enabled) return normalized + providerId
    if (providerId !in normalized || normalized.size <= 1) return normalized
    return normalized - providerId
}
