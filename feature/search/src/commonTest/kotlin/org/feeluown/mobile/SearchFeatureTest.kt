@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SearchFeatureTest {
    @Test
    fun ownerOwnsUiStateAndDispatchesSearchActions() = runTest {
        val persistedPreferences = mutableListOf<Pair<SearchScope, String?>>()
        val owner = createOwner(
            scope = this,
            onPreferencesChanged = { searchScope, providerId -> persistedPreferences += searchScope to providerId },
        )

        assertEquals(ProviderSearchTab.Comprehensive, owner.uiState.value.providerSearchTab)

        owner.dispatch(SearchAction.ScopeChanged(SearchScope.Local))
        owner.dispatch(SearchAction.ProviderChanged("netease"))
        owner.dispatch(SearchAction.QueryChanged("hello"))
        owner.dispatch(SearchAction.ProviderTabChanged(ProviderSearchTab.Albums))

        assertEquals("hello", owner.uiState.value.query)
        assertEquals(SearchScope.Provider, owner.uiState.value.searchScope)
        assertEquals("netease", owner.uiState.value.selectedSearchProviderId)
        assertEquals(ProviderSearchTab.Albums, owner.uiState.value.providerSearchTab)
        assertEquals(
            listOf(
                SearchScope.Local to null,
                SearchScope.Provider to "netease",
            ),
            persistedPreferences,
        )
    }

    @Test
    fun applyingPersistedPreferencesDoesNotWriteThemBack() = runTest {
        var writes = 0
        val owner = createOwner(
            scope = this,
            onPreferencesChanged = { _, _ -> writes += 1 },
        )

        owner.applyPreferences(SearchScope.Provider, "netease")

        assertEquals(SearchScope.Provider, owner.uiState.value.searchScope)
        assertEquals("netease", owner.uiState.value.selectedSearchProviderId)
        assertEquals(0, writes)
    }

    @Test
    fun globalSearchMergesLocalAndProviderTracksByIdentity() = runTest {
        val local = listOf(TestTrack("local:1"), TestTrack("shared"))
        val provider = TestProviderResults(
            tracks = listOf(TestTrack("shared"), TestTrack("netease:2")),
            playlistCount = 1,
        )
        val owner = createOwner(
            scope = this,
            localSearch = { local },
            providerSearch = { _, _ -> provider },
        )

        owner.dispatch(SearchAction.QueryChanged("song"))
        owner.dispatch(SearchAction.Submit)
        advanceUntilIdle()

        assertEquals(listOf("local:1", "shared", "netease:2"), owner.uiState.value.searchResults.map { it.id })
        assertEquals(provider, owner.uiState.value.providerSearchResults)
        assertEquals("搜索到 4 项", owner.uiState.value.message)
        assertFalse(owner.uiState.value.isLoading)
    }

    @Test
    fun recognitionResultResetsScopeAndBuildsSearchQuery() = runTest {
        var opened = 0
        val owner = createOwner(
            scope = this,
            openSearch = { opened += 1 },
        )
        owner.applyPreferences(SearchScope.Provider, "netease")
        owner.dispatch(SearchAction.ProviderTabChanged(ProviderSearchTab.Playlists))

        owner.searchRecognitionResult("Song", listOf("Artist A", "Artist B"))
        advanceUntilIdle()

        assertEquals("Song Artist A / Artist B", owner.uiState.value.query)
        assertEquals(SearchScope.All, owner.uiState.value.searchScope)
        assertEquals(null, owner.uiState.value.selectedSearchProviderId)
        assertEquals(ProviderSearchTab.Comprehensive, owner.uiState.value.providerSearchTab)
        assertEquals(1, opened)
    }

    private fun createOwner(
        scope: CoroutineScope,
        localSearch: suspend (String) -> List<TestTrack> = { emptyList() },
        providerSearch: suspend (String, String?) -> TestProviderResults = { _, _ -> TestProviderResults() },
        openSearch: () -> Unit = {},
        onPreferencesChanged: (SearchScope, String?) -> Unit = { _, _ -> },
    ): SearchFeatureOwner<TestTrack, TestProviderResults> = createSearchFeatureOwner(
        providerRepository = SearchProviderRepository(providerSearch),
        localRepository = SearchLocalRepository(localSearch),
        resultOperations = TestResultOperations,
        scope = scope,
        providerIdsForSearch = { listOf("netease") },
        providerExists = { it == "netease" },
        openSearch = openSearch,
        onPreferencesChanged = onPreferencesChanged,
        failureMessage = { throwable, _ -> throwable.message ?: "search failed" },
        initialState = SearchFeatureState(providerSearchResults = TestProviderResults()),
    )
}

private data class TestTrack(val id: String)

private data class TestProviderResults(
    val tracks: List<TestTrack> = emptyList(),
    val playlistCount: Int = 0,
    val errorMessage: String? = null,
)

private object TestResultOperations : SearchResultOperations<TestTrack, TestProviderResults> {
    override fun empty(errorMessage: String?): TestProviderResults = TestProviderResults(errorMessage = errorMessage)

    override fun tracks(results: TestProviderResults): List<TestTrack> = results.tracks

    override fun merge(results: List<TestProviderResults>): TestProviderResults = TestProviderResults(
        tracks = results.flatMap { it.tracks }.distinctBy { it.id },
        playlistCount = results.sumOf { it.playlistCount },
        errorMessage = results.firstNotNullOfOrNull { it.errorMessage },
    )

    override fun totalCount(results: TestProviderResults): Int = results.tracks.size + results.playlistCount

    override fun errorMessage(results: TestProviderResults): String? = results.errorMessage

    override fun trackId(track: TestTrack): String = track.id
}
