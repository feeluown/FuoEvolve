package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class SearchController(
    private val providerRepository: ProviderMusicRepository,
    private val localRepository: LocalMusicRepository,
    private val scope: CoroutineScope,
    private val state: SearchControllerState,
    private val providerIdsForSearch: () -> List<String>,
    private val providerExists: (String) -> Boolean,
    private val openSearch: () -> Unit,
    private val persistSettings: () -> Unit,
    private val setLoading: (Boolean) -> Unit,
    private val setMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    fun normalizeProviderSelection(providerIds: Set<String>) {
        if (state.selectedSearchProviderId !in providerIds) {
            state.selectedSearchProviderId = null
            if (state.searchScope == SearchScope.Provider) {
                state.searchScope = SearchScope.All
            }
        }
    }

    fun onQueryChange(value: String) {
        state.query = value
    }

    fun onScopeChange(value: SearchScope) {
        state.searchScope = value
        if (value != SearchScope.Provider) {
            state.selectedSearchProviderId = null
        }
        persistSettings()
        if (state.query.isNotBlank()) search()
    }

    fun onProviderChange(providerId: String) {
        state.searchScope = SearchScope.Provider
        state.selectedSearchProviderId = providerId
        persistSettings()
        if (state.query.isNotBlank()) search()
    }

    fun onProviderTabChange(value: ProviderSearchTab) {
        state.providerSearchTab = value
    }

    fun searchRecognizedSong(song: RecognizedSong) {
        state.query = buildList {
            song.title.trim().takeIf { it.isNotBlank() }?.let(::add)
            song.artists.joinToString(" / ").trim().takeIf { it.isNotBlank() }?.let(::add)
        }.joinToString(" ")
        state.searchScope = SearchScope.All
        state.selectedSearchProviderId = null
        state.providerSearchTab = ProviderSearchTab.Songs
        openSearch()
        search()
    }

    fun searchText(text: String, providerId: String?) {
        val keyword = text.trim()
        if (keyword.isBlank()) {
            setMessage("没有可搜索的信息")
            return
        }
        state.query = keyword
        if (providerId != null && providerExists(providerId)) {
            state.searchScope = SearchScope.Provider
            state.selectedSearchProviderId = providerId
        }
        openSearch()
        search()
    }

    fun search() {
        val keyword = state.query.trim()
        if (keyword.isEmpty()) {
            state.searchResults = emptyList()
            state.providerSearchResults = ProviderSearchResults()
            setMessage("请输入关键词")
            return
        }
        scope.launch {
            setLoading(true)
            setMessage("正在搜索：$keyword")
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
            }.onSuccess {
                state.searchResults = it
                val total = when (state.searchScope) {
                    SearchScope.Local -> it.size
                    SearchScope.Provider,
                    SearchScope.All -> state.providerSearchResults.totalCount() + if (state.searchScope == SearchScope.All) {
                        localOnlyCount(it, state.providerSearchResults.tracks)
                    } else {
                        0
                    }
                }
                setMessage(
                    when {
                        total == 0 && state.providerSearchResults.errorMessage != null ->
                            state.providerSearchResults.errorMessage.orEmpty()
                        total == 0 -> "没有搜索结果"
                        else -> "搜索到 $total 项"
                    }
                )
            }.onFailure(onError)
            setLoading(false)
        }
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
        tracks = flatMap { it.tracks },
        playlists = flatMap { it.playlists },
        artists = flatMap { it.artists },
        albums = flatMap { it.albums },
        videos = flatMap { it.videos },
        errorMessage = firstNotNullOfOrNull { it.errorMessage },
    )
}
