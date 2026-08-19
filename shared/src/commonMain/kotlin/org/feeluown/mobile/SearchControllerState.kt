package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SearchUiState(
    val query: String = "",
    val searchScope: SearchScope = SearchScope.All,
    val selectedSearchProviderId: String? = null,
    val searchResults: List<MusicTrack> = emptyList(),
    val providerSearchResults: ProviderSearchResults = ProviderSearchResults(),
    val providerSearchTab: ProviderSearchTab = ProviderSearchTab.Songs,
    val isLoading: Boolean = false,
    val message: String? = null,
)

/** Mutable state owner kept internal to the search feature. */
internal class SearchControllerState(
    initialState: SearchUiState = SearchUiState(),
) {
    private val mutableUiState = MutableStateFlow(initialState)
    val uiState: StateFlow<SearchUiState> = mutableUiState.asStateFlow()

    fun update(transform: (SearchUiState) -> SearchUiState) {
        mutableUiState.value = transform(mutableUiState.value)
    }

    var query: String
        get() = mutableUiState.value.query
        set(value) = update { it.copy(query = value) }

    var searchScope: SearchScope
        get() = mutableUiState.value.searchScope
        set(value) = update { it.copy(searchScope = value) }

    var selectedSearchProviderId: String?
        get() = mutableUiState.value.selectedSearchProviderId
        set(value) = update { it.copy(selectedSearchProviderId = value) }

    var searchResults: List<MusicTrack>
        get() = mutableUiState.value.searchResults
        set(value) = update { it.copy(searchResults = value) }

    var providerSearchResults: ProviderSearchResults
        get() = mutableUiState.value.providerSearchResults
        set(value) = update { it.copy(providerSearchResults = value) }

    var providerSearchTab: ProviderSearchTab
        get() = mutableUiState.value.providerSearchTab
        set(value) = update { it.copy(providerSearchTab = value) }

    var isLoading: Boolean
        get() = mutableUiState.value.isLoading
        set(value) = update { it.copy(isLoading = value) }

    var message: String?
        get() = mutableUiState.value.message
        set(value) = update { it.copy(message = value) }
}
