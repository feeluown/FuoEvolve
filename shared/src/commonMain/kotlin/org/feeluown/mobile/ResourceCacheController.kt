package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

internal class ResourceCacheController(
    private val repository: ResourceCacheRepository,
    private val state: SettingsControllerState,
    private val scope: CoroutineScope,
    private val persistSettings: () -> Unit,
    private val setLoading: (Boolean) -> Unit,
    private val setMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    fun startUsageCollection() {
        scope.launch {
            repository.usage.collect {
                state.cacheUsage = it
            }
        }
    }

    suspend fun updateLimit() {
        repository.updateLimit(
            CacheLimit(
                audioMaxBytes = state.audioCacheLimitMb.mbToBytes(),
                imageMaxBytes = state.imageCacheLimitMb.mbToBytes(),
            ),
        )
    }

    suspend fun refreshUsageNow() {
        repository.refreshUsage()
    }

    fun onAudioCacheLimitChange(value: Int) {
        state.audioCacheLimitMb = value
        persistSettings()
        scope.launch {
            updateLimit()
            repository.refreshUsage()
        }
    }

    fun onImageCacheLimitChange(value: Int) {
        state.imageCacheLimitMb = value
        persistSettings()
        scope.launch {
            updateLimit()
            repository.refreshUsage()
        }
    }

    fun refreshUsage() {
        scope.launch {
            runCatching { repository.refreshUsage() }
                .onFailure(onError)
        }
    }

    fun clear() {
        scope.launch {
            setLoading(true)
            setMessage("正在清空缓存")
            runCatching {
                repository.clearAll()
                repository.refreshUsage()
            }.onSuccess {
                setMessage("缓存已清空")
            }.onFailure(onError)
            setLoading(false)
        }
    }

    private fun Int.mbToBytes(): Long = toLong() * 1024L * 1024L
}
