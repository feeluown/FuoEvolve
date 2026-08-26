package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope

typealias SearchFeatureController = SearchFeatureOwner<MusicTrack, ProviderSearchResults>
typealias SearchUiState = SearchFeatureState<MusicTrack, ProviderSearchResults>

/** Preserves the existing application-facing state constructor while ownership lives in :feature:search. */
@Suppress("FunctionName")
fun SearchUiState(
    query: String = "",
    searchScope: SearchScope = SearchScope.All,
    selectedSearchProviderId: String? = null,
    searchResults: List<MusicTrack> = emptyList(),
    providerSearchResults: ProviderSearchResults = ProviderSearchResults(),
    providerSearchTab: ProviderSearchTab = ProviderSearchTab.Songs,
    isLoading: Boolean = false,
    message: String? = null,
): SearchUiState = SearchFeatureState(
    providerSearchResults = providerSearchResults,
    query = query,
    searchScope = searchScope,
    selectedSearchProviderId = selectedSearchProviderId,
    searchResults = searchResults,
    providerSearchTab = providerSearchTab,
    isLoading = isLoading,
    message = message,
)

/** Composition-root binding from application repositories to the physical Search feature module. */
fun createSearchFeatureController(
    providerRepository: ProviderSearchRepository,
    localRepository: LocalMusicRepository,
    scope: CoroutineScope,
    providerIdsForSearch: () -> List<String>,
    providerExists: (String) -> Boolean,
    openSearch: () -> Unit,
    onPreferencesChanged: (SearchScope, String?) -> Unit,
    initialState: SearchUiState = SearchUiState(),
): SearchFeatureController = createSearchFeatureOwner(
    providerRepository = SearchProviderRepository { keyword, providerId ->
        providerRepository.searchAll(keyword, providerId)
    },
    localRepository = SearchLocalRepository(localRepository::search),
    resultOperations = AppSearchResultOperations,
    scope = scope,
    providerIdsForSearch = providerIdsForSearch,
    providerExists = providerExists,
    openSearch = openSearch,
    onPreferencesChanged = onPreferencesChanged,
    failureMessage = { throwable, providerId ->
        throwable.providerFailureOrNull(providerId)?.userMessage
            ?: throwable.message
            ?: throwable::class.simpleName.orEmpty()
    },
    initialState = initialState,
)

/** Keeps Recognition -> Search integration primitive at the physical feature boundary. */
fun SearchFeatureController.searchRecognizedSong(song: RecognizedSong) {
    searchRecognitionResult(song.title, song.artists)
}

private object AppSearchResultOperations : SearchResultOperations<MusicTrack, ProviderSearchResults> {
    override fun empty(errorMessage: String?): ProviderSearchResults = ProviderSearchResults(errorMessage = errorMessage)

    override fun tracks(results: ProviderSearchResults): List<MusicTrack> = results.tracks

    override fun merge(results: List<ProviderSearchResults>): ProviderSearchResults = ProviderSearchResults(
        tracks = results.flatMap { it.tracks }.distinctBy { it.id },
        playlists = results.flatMap { it.playlists }.distinctBy { it.id },
        artists = results.flatMap { it.artists }.distinctBy { it.id },
        albums = results.flatMap { it.albums }.distinctBy { it.id },
        videos = results.flatMap { it.videos }.distinctBy { it.id },
        errorMessage = results.firstNotNullOfOrNull { it.errorMessage },
    )

    override fun totalCount(results: ProviderSearchResults): Int =
        results.tracks.size +
            results.playlists.size +
            results.artists.size +
            results.albums.size +
            results.videos.size

    override fun errorMessage(results: ProviderSearchResults): String? = results.errorMessage

    override fun trackId(track: MusicTrack): String = track.id
}
