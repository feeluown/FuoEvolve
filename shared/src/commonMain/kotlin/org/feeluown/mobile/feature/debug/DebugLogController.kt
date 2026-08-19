package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class DebugLogController(
    private val repository: DebugLogRepository,
    private val state: SettingsControllerState,
    private val scope: CoroutineScope,
    private val setLoading: (Boolean) -> Unit,
    private val setMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    val isAvailable: Boolean
        get() = repository.isAvailable

    fun refresh() {
        if (!repository.isAvailable) return
        scope.launch {
            setLoading(true)
            state.debugLogError = null
            runCatching { repository.logLines() }
                .onSuccess { state.debugLogLines = it }
                .onFailure { state.debugLogError = it.message ?: it::class.simpleName.orEmpty() }
            setLoading(false)
        }
    }

    fun onLevelFilterChange(level: DebugLogLevel, selected: Boolean) {
        state.debugLogLevelFilters = if (selected) {
            state.debugLogLevelFilters + level
        } else {
            state.debugLogLevelFilters - level
        }
    }

    fun export(lines: List<String>) {
        if (!repository.isAvailable || lines.isEmpty()) return
        scope.launch {
            setLoading(true)
            runCatching { repository.exportLogFile(lines) }
                .onSuccess(setMessage)
                .onFailure(onError)
            setLoading(false)
        }
    }
}
