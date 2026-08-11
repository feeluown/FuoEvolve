package org.feeluown.mobile

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private const val SEARCH_HISTORY_PREFERENCES = "fuoevolve_search_history"
private const val SEARCH_HISTORY_KEY = "history"

@Composable
internal actual fun rememberSearchHistoryStore(): SearchHistoryStore {
    val context = LocalContext.current.applicationContext
    return remember(context) {
        AndroidSearchHistoryStore(context)
    }
}

private class AndroidSearchHistoryStore(context: Context) : SearchHistoryStore {
    private val preferences = context.getSharedPreferences(
        SEARCH_HISTORY_PREFERENCES,
        Context.MODE_PRIVATE,
    )

    override fun load(): List<String> =
        decodeSearchHistory(preferences.getString(SEARCH_HISTORY_KEY, null))

    override fun save(history: List<String>) {
        preferences.edit()
            .putString(SEARCH_HISTORY_KEY, encodeSearchHistory(history))
            .apply()
    }
}
