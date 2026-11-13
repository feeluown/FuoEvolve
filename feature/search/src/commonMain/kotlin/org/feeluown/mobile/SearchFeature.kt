package org.feeluown.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

@Serializable
enum class SearchScope {
    Local,
    Provider,
    All,
}

@Serializable
enum class ProviderSearchTab {
    Comprehensive,
    Songs,
    Artists,
    Albums,
    Playlists,
    Videos,
}

sealed interface SearchAction {
    data class QueryChanged(val value: String) : SearchAction
    data class ScopeChanged(val value: SearchScope) : SearchAction
    data class ProviderChanged(val providerId: String) : SearchAction
    data class ProviderTabChanged(val value: ProviderSearchTab) : SearchAction
    data object Submit : SearchAction
}

data class SearchFeatureState<Track, ProviderResults>(
    val providerSearchResults: ProviderResults,
    val query: String = "",
    val searchScope: SearchScope = SearchScope.All,
    val selectedSearchProviderId: String? = null,
    val searchResults: List<Track> = emptyList(),
    val providerSearchTab: ProviderSearchTab = ProviderSearchTab.Comprehensive,
    val isLoading: Boolean = false,
    val message: String? = null,
    /**
     * Per-provider failures observed during the last search (providerId -> user-facing message).
     *
     * Multi-source search isolates each provider, so one broken source (for example an expired
     * QQ Music session) no longer discards the healthy providers' results. The UI can surface
     * these entries as a partial-failure warning; [message] stays the human-readable summary.
     */
    val providerFailures: Map<String, String> = emptyMap(),
)

/**
 * Provider-facing search capability owned by the Search feature boundary.
 *
 * The concrete provider graph is adapted by the application composition layer. Keeping the
 * result type generic lets this module own search orchestration without depending on :shared.
 */
fun interface SearchProviderRepository<ProviderResults> {
    suspend fun searchAll(keyword: String, providerId: String?): ProviderResults
}

/** Local-library capability required by Search. */
fun interface SearchLocalRepository<Track> {
    suspend fun search(keyword: String): List<Track>
}

/** Operations Search needs to inspect provider results without knowing application domain models. */
interface SearchResultOperations<Track, ProviderResults> {
    fun empty(errorMessage: String? = null): ProviderResults
    fun tracks(results: ProviderResults): List<Track>
    fun merge(results: List<ProviderResults>): ProviderResults
    fun totalCount(results: ProviderResults): Int
    fun errorMessage(results: ProviderResults): String?
    fun trackId(track: Track): String
}

/** Search state/actions exposed to the app shell. */
interface SearchFeatureOwner<Track, ProviderResults> {
    val uiState: StateFlow<SearchFeatureState<Track, ProviderResults>>

    fun dispatch(action: SearchAction)

    fun clearResults()

    fun applyPreferences(
        searchScope: SearchScope,
        selectedSearchProviderId: String?,
    )

    fun normalizeProviderSelection(providerIds: Set<String>)

    fun searchRecognitionResult(title: String, artists: List<String>)

    fun searchText(text: String, providerId: String?)
}

fun <Track, ProviderResults> createSearchFeatureOwner(
    providerRepository: SearchProviderRepository<ProviderResults>,
    localRepository: SearchLocalRepository<Track>,
    resultOperations: SearchResultOperations<Track, ProviderResults>,
    scope: CoroutineScope,
    providerIdsForSearch: () -> List<String>,
    providerExists: (String) -> Boolean,
    openSearch: () -> Unit,
    onPreferencesChanged: (SearchScope, String?) -> Unit,
    failureMessage: (Throwable, String?) -> String,
    initialState: SearchFeatureState<Track, ProviderResults>,
): SearchFeatureOwner<Track, ProviderResults> = SearchController(
    providerRepository = providerRepository,
    localRepository = localRepository,
    resultOperations = resultOperations,
    scope = scope,
    state = SearchControllerState(initialState),
    providerIdsForSearch = providerIdsForSearch,
    providerExists = providerExists,
    openSearch = openSearch,
    onPreferencesChanged = onPreferencesChanged,
    failureMessage = failureMessage,
)

private class SearchControllerState<Track, ProviderResults>(
    initialState: SearchFeatureState<Track, ProviderResults>,
) {
    private val mutableUiState = MutableStateFlow(initialState)
    val uiState: StateFlow<SearchFeatureState<Track, ProviderResults>> = mutableUiState.asStateFlow()

    fun update(transform: (SearchFeatureState<Track, ProviderResults>) -> SearchFeatureState<Track, ProviderResults>) {
        mutableUiState.value = transform(mutableUiState.value)
    }
}

/** Outcome of one search execution, including any providers that failed in multi-source mode. */
private data class SearchExecution<Track, ProviderResults>(
    val tracks: List<Track>,
    val providerResults: ProviderResults,
    val providerFailures: Map<String, String> = emptyMap(),
)

/** Owns search state and search-specific orchestration. */
private class SearchController<Track, ProviderResults>(
    private val providerRepository: SearchProviderRepository<ProviderResults>,
    private val localRepository: SearchLocalRepository<Track>,
    private val resultOperations: SearchResultOperations<Track, ProviderResults>,
    private val scope: CoroutineScope,
    private val state: SearchControllerState<Track, ProviderResults>,
    private val providerIdsForSearch: () -> List<String>,
    private val providerExists: (String) -> Boolean,
    private val openSearch: () -> Unit,
    private val onPreferencesChanged: (SearchScope, String?) -> Unit,
    private val failureMessage: (Throwable, String?) -> String,
) : SearchFeatureOwner<Track, ProviderResults> {
    override val uiState: StateFlow<SearchFeatureState<Track, ProviderResults>> = state.uiState

    private var searchJob: Job? = null
    private var searchGeneration = 0L

    override fun dispatch(action: SearchAction) {
        when (action) {
            is SearchAction.QueryChanged -> onQueryChange(action.value)
            is SearchAction.ScopeChanged -> onScopeChange(action.value)
            is SearchAction.ProviderChanged -> onProviderChange(action.providerId)
            is SearchAction.ProviderTabChanged -> onProviderTabChange(action.value)
            SearchAction.Submit -> search()
        }
    }

    override fun applyPreferences(
        searchScope: SearchScope,
        selectedSearchProviderId: String?,
    ) {
        state.update { current ->
            current.copy(
                searchScope = searchScope,
                selectedSearchProviderId = selectedSearchProviderId,
            )
        }
    }

    override fun clearResults() {
        searchGeneration += 1L
        searchJob?.cancel()
        searchJob = null
        state.update {
            it.copy(
                searchResults = emptyList(),
                providerSearchResults = resultOperations.empty(),
                isLoading = false,
                message = null,
                providerFailures = emptyMap(),
            )
        }
    }

    override fun normalizeProviderSelection(providerIds: Set<String>) {
        val current = state.uiState.value
        if (current.selectedSearchProviderId !in providerIds) {
            val previousScope = current.searchScope
            val previousProviderId = current.selectedSearchProviderId
            state.update {
                it.copy(
                    selectedSearchProviderId = null,
                    searchScope = if (it.searchScope == SearchScope.Provider) SearchScope.All else it.searchScope,
                )
            }
            val updated = state.uiState.value
            if (
                previousScope != updated.searchScope ||
                previousProviderId != updated.selectedSearchProviderId
            ) {
                notifyPreferencesChanged()
            }
        }
    }

    private fun onQueryChange(value: String) {
        state.update { it.copy(query = value) }
    }

    private fun onScopeChange(value: SearchScope) {
        state.update { current ->
            current.copy(
                searchScope = value,
                selectedSearchProviderId = current.selectedSearchProviderId.takeIf { value == SearchScope.Provider },
            )
        }
        notifyPreferencesChanged()
        if (state.uiState.value.query.isNotBlank()) search()
    }

    private fun onProviderChange(providerId: String) {
        state.update {
            it.copy(
                searchScope = SearchScope.Provider,
                selectedSearchProviderId = providerId,
            )
        }
        notifyPreferencesChanged()
        if (state.uiState.value.query.isNotBlank()) search()
    }

    private fun onProviderTabChange(value: ProviderSearchTab) {
        state.update { it.copy(providerSearchTab = value) }
    }

    override fun searchRecognitionResult(title: String, artists: List<String>) {
        state.update {
            it.copy(
                query = buildList {
                    title.trim().takeIf(String::isNotBlank)?.let(::add)
                    artists.joinToString(" / ").trim().takeIf(String::isNotBlank)?.let(::add)
                }.joinToString(" "),
                searchScope = SearchScope.All,
                selectedSearchProviderId = null,
                providerSearchTab = ProviderSearchTab.Comprehensive,
            )
        }
        notifyPreferencesChanged()
        openSearch()
        search()
    }

    override fun searchText(text: String, providerId: String?) {
        val keyword = text.trim()
        if (keyword.isBlank()) {
            state.update { it.copy(message = "没有可搜索的信息") }
            return
        }
        state.update { current ->
            if (providerId != null && providerExists(providerId)) {
                current.copy(
                    query = keyword,
                    searchScope = SearchScope.Provider,
                    selectedSearchProviderId = providerId,
                )
            } else {
                current.copy(query = keyword)
            }
        }
        notifyPreferencesChanged()
        openSearch()
        search()
    }

    private fun search() {
        val generation = ++searchGeneration
        searchJob?.cancel()
        searchJob = null
        val keyword = state.uiState.value.query.trim()
        if (keyword.isEmpty()) {
            state.update {
                it.copy(
                    searchResults = emptyList(),
                    providerSearchResults = resultOperations.empty(),
                    isLoading = false,
                    message = "请输入关键词",
                    providerFailures = emptyMap(),
                )
            }
            return
        }

        searchJob = scope.launch {
            if (generation != searchGeneration) return@launch
            state.update {
                it.copy(
                    isLoading = true,
                    message = "正在搜索：$keyword",
                    providerFailures = emptyMap(),
                )
            }
            try {
                val execution = withTimeout(25_000) {
                    searchInCurrentScope(keyword)
                }
                if (generation == searchGeneration) {
                    state.update { current ->
                        val results = execution.tracks
                        val total = when (current.searchScope) {
                            SearchScope.Local -> results.size
                            SearchScope.Provider,
                            SearchScope.All -> resultOperations.totalCount(execution.providerResults) +
                                if (current.searchScope == SearchScope.All) {
                                    localOnlyCount(results, resultOperations.tracks(execution.providerResults))
                                } else {
                                    0
                                }
                        }
                        val providerError = resultOperations.errorMessage(execution.providerResults)
                        val failures = execution.providerFailures
                        current.copy(
                            searchResults = results,
                            providerSearchResults = execution.providerResults,
                            providerFailures = failures,
                            message = when {
                                total > 0 -> "搜索到 $total 项"
                                failures.isNotEmpty() -> failures.values.distinct().joinToString("；")
                                providerError != null -> providerError
                                else -> "没有搜索结果"
                            },
                        )
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                if (generation == searchGeneration) {
                    val current = state.uiState.value
                    val failure = failureMessage(timeout, current.selectedSearchProviderId)
                    state.update {
                        it.copy(
                            searchResults = emptyList(),
                            providerSearchResults = resultOperations.empty(errorMessage = failure),
                            message = failure,
                            providerFailures = emptyMap(),
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (exception: Exception) {
                if (generation == searchGeneration) {
                    val current = state.uiState.value
                    val failure = failureMessage(exception, current.selectedSearchProviderId)
                    state.update {
                        it.copy(
                            searchResults = emptyList(),
                            providerSearchResults = resultOperations.empty(errorMessage = failure),
                            message = failure,
                            providerFailures = emptyMap(),
                        )
                    }
                }
            } finally {
                if (generation == searchGeneration) {
                    searchJob = null
                    state.update { it.copy(isLoading = false) }
                }
            }
        }
    }

    private suspend fun searchInCurrentScope(keyword: String): SearchExecution<Track, ProviderResults> =
        when (state.uiState.value.searchScope) {
            SearchScope.Local -> SearchExecution(
                tracks = localRepository.search(keyword),
                providerResults = resultOperations.empty(),
            )

            SearchScope.Provider -> {
                val provider = providerRepository.searchAll(
                    keyword,
                    state.uiState.value.selectedSearchProviderId,
                )
                SearchExecution(
                    tracks = resultOperations.tracks(provider),
                    providerResults = provider,
                )
            }

            SearchScope.All -> searchAllSources(keyword)
        }

    /**
     * Multi-source search with per-provider failure isolation.
     *
     * One unhealthy source (for example QQ Music with a server-side expired session) must not
     * discard the results of the healthy ones, and must not fail the whole search. Each provider
     * outcome is captured independently; successful results are merged and failures are reported
     * through [SearchFeatureState.providerFailures].
     */
    private suspend fun searchAllSources(keyword: String): SearchExecution<Track, ProviderResults> =
        coroutineScope {
            val localDeferred = async { localRepository.search(keyword) }
            val providerDeferreds = providerIdsForSearch().map { providerId ->
                async { providerId to searchProviderIsolated(keyword, providerId) }
            }
            val local = localDeferred.await()
            val providerOutcomes = providerDeferreds.awaitAll()

            val successes = mutableListOf<ProviderResults>()
            val failures = linkedMapOf<String, String>()
            providerOutcomes.forEach { (providerId, outcome) ->
                outcome.fold(
                    onSuccess = { successes += it },
                    onFailure = { failures[providerId] = failureMessage(it, providerId) },
                )
            }

            val merged = if (successes.isEmpty()) {
                val failureText = failures.values.distinct().joinToString("；")
                resultOperations.empty(errorMessage = failureText.takeIf { failures.isNotEmpty() })
            } else {
                resultOperations.merge(successes)
            }
            SearchExecution(
                tracks = mergeResults(local, resultOperations.tracks(merged)),
                providerResults = merged,
                providerFailures = failures,
            )
        }

    private suspend fun searchProviderIsolated(
        keyword: String,
        providerId: String,
    ): Result<ProviderResults> = try {
        Result.success(providerRepository.searchAll(keyword, providerId))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (exception: Exception) {
        Result.failure(exception)
    }

    private fun notifyPreferencesChanged() {
        val current = state.uiState.value
        onPreferencesChanged(current.searchScope, current.selectedSearchProviderId)
    }

    private fun mergeResults(local: List<Track>, provider: List<Track>): List<Track> {
        val seen = linkedSetOf<String>()
        return (local + provider).filter { seen.add(resultOperations.trackId(it)) }
    }

    private fun localOnlyCount(allTracks: List<Track>, providerTracks: List<Track>): Int {
        if (providerTracks.isEmpty()) return allTracks.size
        val providerIds = providerTracks.map(resultOperations::trackId).toSet()
        return allTracks.count { resultOperations.trackId(it) !in providerIds }
    }
}
