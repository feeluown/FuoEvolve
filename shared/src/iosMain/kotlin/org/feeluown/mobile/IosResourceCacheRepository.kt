package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSURLCache

class IosResourceCacheRepository : ResourceCacheRepository {
    private val mutableUsage = MutableStateFlow(CacheUsage())
    private var limit = CacheLimit(
        audioMaxBytes = DEFAULT_AUDIO_CACHE_LIMIT_MB.toLong() * 1024L * 1024L,
        imageMaxBytes = DEFAULT_IMAGE_CACHE_LIMIT_MB.toLong() * 1024L * 1024L,
    )

    override val usage: StateFlow<CacheUsage> = mutableUsage.asStateFlow()

    override suspend fun refreshUsage() {
        val cache = NSURLCache.sharedURLCache
        mutableUsage.value = CacheUsage(
            audioBytes = 0,
            imageBytes = cache.currentDiskUsage.toLong() + cache.currentMemoryUsage.toLong(),
        )
    }

    override suspend fun clearAll() {
        NSURLCache.sharedURLCache.removeAllCachedResponses()
        mutableUsage.value = CacheUsage()
    }

    override suspend fun updateLimit(limit: CacheLimit) {
        this.limit = limit
        NSURLCache.sharedURLCache.diskCapacity = limit.imageMaxBytes.toULong()
        NSURLCache.sharedURLCache.memoryCapacity = minOf(limit.imageMaxBytes / 4, 32L * 1024L * 1024L).toULong()
        refreshUsage()
    }
}
