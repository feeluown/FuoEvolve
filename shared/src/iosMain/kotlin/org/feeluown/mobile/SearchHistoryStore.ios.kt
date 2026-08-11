package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import platform.Foundation.NSUserDefaults

private const val SEARCH_HISTORY_KEY = "fuoevolve_search_history"

@Composable
internal actual fun rememberSearchHistoryStore(): SearchHistoryStore =
    remember {
        IosSearchHistoryStore(NSUserDefaults.standardUserDefaults)
    }

private class IosSearchHistoryStore(
    private val defaults: NSUserDefaults,
) : SearchHistoryStore {
    override fun load(): List<String> =
        decodeSearchHistory(defaults.stringForKey(SEARCH_HISTORY_KEY))

    override fun save(history: List<String>) {
        defaults.setObject(encodeSearchHistory(history), SEARCH_HISTORY_KEY)
        defaults.synchronize()
    }
}
