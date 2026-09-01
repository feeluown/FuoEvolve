package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import java.util.prefs.Preferences

private const val SEARCH_HISTORY_KEY = "search_history"

@Composable
internal actual fun rememberSearchHistoryStore(): SearchHistoryStore = remember {
    val preferences = Preferences.userRoot().node("org/feeluown/mobile")
    object : SearchHistoryStore {
        override fun load(): List<String> = decodeSearchHistory(preferences.get(SEARCH_HISTORY_KEY, null))

        override fun save(history: List<String>) {
            preferences.put(SEARCH_HISTORY_KEY, encodeSearchHistory(history))
        }
    }
}
