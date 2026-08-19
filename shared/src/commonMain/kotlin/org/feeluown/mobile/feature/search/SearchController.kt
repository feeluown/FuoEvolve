package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.feeluown.mobile.provider.api.ProviderAvailability

sealed interface SearchAction {
    data class QueryChanged(val value: String) : SearchAction
    data class ScopeChanged(val value: SearchScope) : SearchAction
    data class ProviderChanged(val providerId: String) : SearchAction
    data class ProviderTabChanged(val value: ProviderSearchTab) : SearchAction
    data object Submit : SearchAction
}

/**
 * Search feature boundary consumed by the app shell and compatibility callers.
 *
 * The app composition root owns the concrete controller. A compatibility facade may delegate to
 * the same instance while the rest of the application is migrated, but it must not create a
 * second search state when an owner is supplied.
 */
interface SearchFeatureController {
    val uiState: StateFlow<SearchUiState>

    fun dispatch(action: SearchAction)

    fun applyPreferences(
        searchScope: SearchScope,
        selectedSearchProviderId: String?,
    )

    fun normalizeProviderSelection(providerIds: Set<String>)

    fun searchRecognizedSong(song: RecognizedSong)

    fun searchText(text: String, providerId: String?)
}

/**
 * Composition-root factory. The aggregate provider repository is adapted here so the feature
 * implementation itself remains dependent on narrow provider boundaries.
 */
fun createSearchFeatureController(
    providerRepository: ProviderMusicRepository,
    localRepository: LocalMusicRepository,
    scope: CoroutineScope,
    providerIdsForSearch: () -> List<String>,
    providerExists: (String) -> Boolean,
    openSearch: () -> Unit,
    onPreferencesChanged: (SearchScope, String?) -> Unit,
    initialState: SearchUiState = SearchUiState(),
): SearchFeatureController = SearchController(
    providerRepository = ProviderSearchRepositoryView(providerRepository),
    localRepository = localRepository,
    scope = scope,
    providerIdsForSearch = providerIdsForSearch,
    providerExists = providerExists,
    openSearch = openSearch,
    onPreferencesChanged = onPreferencesChanged,
    initialState = initialState,
)

/** Owns search state and search-specific operations. */
internal class SearchController private constructor(
    private val providerRepository: ProviderSearchRepository,
    private val localRepository: LocalMusicRepository,
    private val scope: CoroutineScope,
    private val state: SearchControllerState,
    private val providerIdsForSearch: () -> List<String>,
    private val providerAvailability: ProviderAvailability,
    private val openSearch: () -> Unit,
    private val onPreferencesChanged: (SearchScope, String?) -> Unit,
) : SearchFeatureController {
    constructor(
        providerRepository: ProviderSearchRepository,
        localRepository: LocalMusicRepository,
        scope: CoroutineScope,
        providerIdsForSearch: () -> List<String>,
        providerExists: (String) -> Boolean,
        openSearch: () -> Unit,
        onPreferencesChanged: (SearchScope, String?) -> Unit,
        initialState: SearchUiState = SearchUiState(),
    ) : this(
        providerRepository = providerRepository,
        localRepository = localRepository,
        scope = scope,
        state = SearchControllerState(initialState),
        providerIdsForSearch = providerIdsForSearch,
        providerAvailability = ProviderAvailability { providerId -> providerExists(providerId) },
        openSearch = openSearch,
        onPreferencesChanged = onPreferencesChanged,
    )

    /** Compatibility constructor for standalone legacy controller instances. */
    @Suppress("UNUSED_PARAMETER")
    constructor(
        providerRepository: ProviderMusicRepository,
        localRepository: LocalMusicRepository,
        scope: CoroutineScope,
        state: SearchControllerState,
        providerIdsForSearch: () -> List<String>,
        providerExists: (String) -> Boolean,
        openSearch: () -> Unit,
        persistSettings: () -> Unit,
        setLoading: (Boolean) -> Unit,
        setMessage: (String) -> Unit,
        onError: (Throwable) -> Unit,
    ) : this(
        providerRepository = ProviderSearchRepositoryView(providerRepository),
        localRepository = localRepository,
        scope = scope,
        state = state,
        providerIdsForSearch = providerIdsForSearch,
        providerAvailability = ProviderAvailability { providerId -> providerExists(providerId) },
        openSearch = openSearch,
        onPreferencesChanged = { _, _ -> persistSettings() },
    )

    override val uiState: StateFlow<SearchUiState> = state.uiState

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

    override fun normalizeProviderSelection(providerIds: Set<String>) {
        if (state.selectedSearchProviderId !in providerIds) {
            val previous = state.uiState.value
            state.update { current ->
                current.copy(
                    selectedSearchProviderId = null,
                    searchScope = if (current.searchScope == SearchScope.Provider) SearchScope.All else current.searchScope,
                )
            }
            val current = state.uiState.value
            if (
                previous.searchScope != current.searchScope ||
                previous.selectedSearchProviderId != current.selectedSearchProviderId
            ) {
                notifyPreferencesChanged()
            }
        }
    }

    fun onQueryChange(value: String) {
        state.query = value
    }

    fun onScopeChange(value: SearchScope) {
        state.update { current ->
            current.copy(
                searchScope = value,
                selectedSearchProviderId = current.selectedSearchProviderId.takeIf { value == SearchScope.Provider },
            )
        }
        notifyPreferencesChanged()
        if (state.query.isNotBlank()) search()
    }

    fun onProviderChange(providerId: String) {
        state.update {
            it.copy(
                searchScope = SearchScope.Provider,
                selectedSearchProviderId = providerId,
            )
        }
        notifyPreferencesChanged()
        if (state.query.isNotBlank()) search()
    }

    fun onProviderTabChange(value: ProviderSearchTab) {
        state.providerSearchTab = value
    }

    override fun searchRecognizedSong(song: RecognizedSong) {
        state.update {
            it.copy(
                query = buildList {
                    song.title.trim().takeIf { title -> title.isNotBlank() }?.let(::add)
                    song.artists.joinToString(" / ").trim().takeIf { artists -> artists.isNotBlank() }?.let(::add)
                }.joinToString(" "),
                searchScope = SearchScope.All,
                selectedSearchProviderId = null,
                providerSearchTab = ProviderSearchTab.Songs,
            )
        }
        notifyPreferencesChanged()
        openSearch()
        search()
    }

    override fun searchText(text: String, providerId: String?) {
        val keyword = text.trim()
        if (keyword.isBlank()) {
            state.message = "没有可搜索的信息"
            return
        }
        state.update { current ->
            if (providerId != null && providerAvailability.contains(providerId)) {
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

    fun search() {
        val keyword = state.query.trim()
        if (keyword.isEmpty()) {
            state.update {
                it.copy(
                    searchResults = emptyList(),
                    providerSearchResults = ProviderSearchResults(),
                    isLoading = false,
                    message = "请输入关键词",
                )
            }
            return
        }
        scope.launch {
            state.update { it.copy(isLoading = true, message = "正在搜索：$keyword") }
            runCatching {
                withTimeout(25_000) {
                    when (state.searchScope) {
                        SearchScope.Local -> {
                            val local = localRepository.search(keyword)
                            state.providerSearchResults = ProviderSearchResults()
                            local
                        }
                        SearchScope.Provider -> {
                            val provider = providerRepository.searchAll(keyword, state.selectedSearchProviderId)
                            state.providerSearchResults = provider
                            provider.tracks
                        }
                        SearchScope.All -> {
                            val local = localRepository.search(keyword)
                            val provider = providerIdsForSearch().map { providerId ->
                                providerRepository.searchAll(keyword, providerId)
                            }.mergeSearchResults()
                            state.providerSearchResults = provider
                            mergeResults(local, provider.tracks)
                        }
                    }
                }
            }.onSuccess { results ->
                state.searchResults = results
                val total = when (state.searchScope) {
                    SearchScope.Local -> results.size
                    SearchScope.Provider,
                    SearchScope.All -> state.providerSearchResults.totalCount() + if (state.searchScope == SearchScope.All) {
                        localOnlyCount(results, state.providerSearchResults.tracks)
                    } else {
                        0
                    }
                }
                state.message = when {
                    total == 0 && state.providerSearchResults.errorMessage != null ->
                        state.providerSearchResults.errorMessage.orEmpty()
                    total == 0 -> "没有搜索结果"
                    else -> "搜索到 $total 项"
                }
            }.onFailure { throwable ->
                val failure = throwable.providerFailureOrNull(state.selectedSearchProviderId)?.userMessage
                    ?: throwable.message
                    ?: throwable::class.simpleName.orEmpty()
                state.update { current ->
                    current.copy(
                        searchResults = emptyList(),
                        providerSearchResults = ProviderSearchResults(errorMessage = failure),
                        message = failure,
                    )
                }
            }
            state.isLoading = false
        }
    }

    private fun notifyPreferencesChanged() {
        onPreferencesChanged(state.searchScope, state.selectedSearchProviderId)
    }

    private fun mergeResults(local: List<MusicTrack>, provider: List<MusicTrack>): List<MusicTrack> {
        val seen = linkedSetOf<String>()
        return (local + provider).filter { seen.add(it.id) }
    }

    private fun ProviderSearchResults.totalCount(): Int =
        tracks.size + playlists.size + artists.size + albums.size + videos.size

    private fun localOnlyCount(allTracks: List<MusicTrack>, providerTracks: List<MusicTrack>): Int {
        if (providerTracks.isEmpty()) return allTracks.size
        val providerIds = providerTracks.map { it.id }.toSet()
        return allTracks.count { it.id !in providerIds }
    }

    private fun List<ProviderSearchResults>.mergeSearchResults(): ProviderSearchResults = ProviderSearchResults(
        tracks = flatMap { it.tracks }.distinctBy { it.id },
        playlists = flatMap { it.playlists }.distinctBy { it.id },
        artists = flatMap { it.artists }.distinctBy { it.id },
        albums = flatMap { it.albums }.distinctBy { it.id },
        videos = flatMap { it.videos }.distinctBy { it.id },
        errorMessage = firstNotNullOfOrNull { it.errorMessage },
    )
}
