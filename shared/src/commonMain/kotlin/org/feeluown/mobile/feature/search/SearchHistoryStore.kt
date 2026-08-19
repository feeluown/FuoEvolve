package org.feeluown.mobile

import androidx.compose.runtime.Composable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal interface SearchHistoryStore {
    fun load(): List<String>
    fun save(history: List<String>)
}

@Composable
internal expect fun rememberSearchHistoryStore(): SearchHistoryStore

private val searchHistoryJson = Json {
    ignoreUnknownKeys = true
}

internal fun decodeSearchHistory(raw: String?): List<String> {
    if (raw.isNullOrBlank()) return emptyList()
    return runCatching {
        searchHistoryJson.decodeFromString<List<String>>(raw)
    }.getOrDefault(emptyList())
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinct()
}

internal fun encodeSearchHistory(history: List<String>): String =
    searchHistoryJson.encodeToString(history)
