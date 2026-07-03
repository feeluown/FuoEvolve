package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class IosResourceCacheRepository : ResourceCacheRepository {
    private val mutableUsage = MutableStateFlow(CacheUsage())
    private var limit = CacheLimit(
        audioMaxBytes = DEFAULT_AUDIO_CACHE_LIMIT_MB.toLong() * 1024L * 1024L,
        imageMaxBytes = DEFAULT_IMAGE_CACHE_LIMIT_MB.toLong() * 1024L * 1024L,
    )

    override val usage: StateFlow<CacheUsage> = mutableUsage.asStateFlow()

    override suspend fun refreshUsage() = Unit

    override suspend fun clearAll() {
        mutableUsage.value = CacheUsage()
    }

    override suspend fun updateLimit(limit: CacheLimit) {
        this.limit = limit
    }
}
