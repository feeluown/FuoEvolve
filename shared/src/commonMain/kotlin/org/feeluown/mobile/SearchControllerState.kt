package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class SearchControllerState {
    var query by mutableStateOf("")
    var searchScope by mutableStateOf(SearchScope.All)
    var selectedSearchProviderId by mutableStateOf<String?>(null)
    var searchResults by mutableStateOf<List<MusicTrack>>(emptyList())
    var providerSearchResults by mutableStateOf(ProviderSearchResults())
    var providerSearchTab by mutableStateOf(ProviderSearchTab.Songs)
}
