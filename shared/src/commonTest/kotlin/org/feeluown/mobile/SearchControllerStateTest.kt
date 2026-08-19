package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SearchControllerStateTest {
    @Test
    fun updatesArePublishedAsImmutableUiState() {
        val state = SearchControllerState()

        state.query = "halzion"
        state.searchScope = SearchScope.Provider
        state.selectedSearchProviderId = "netease"
        state.isLoading = true
        state.message = "正在搜索"

        val uiState = state.uiState.value
        assertEquals("halzion", uiState.query)
        assertEquals(SearchScope.Provider, uiState.searchScope)
        assertEquals("netease", uiState.selectedSearchProviderId)
        assertTrue(uiState.isLoading)
        assertEquals("正在搜索", uiState.message)
    }

    @Test
    fun updateProducesANewSnapshotWithoutMutatingPreviousState() {
        val state = SearchControllerState()
        val before = state.uiState.value

        state.update { it.copy(query = "new", isLoading = true) }
        val after = state.uiState.value

        assertEquals("", before.query)
        assertFalse(before.isLoading)
        assertEquals("new", after.query)
        assertTrue(after.isLoading)
    }
}
