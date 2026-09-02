package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Platform boundary for physical resource-cache storage. */
interface ResourceCacheStorage {
    suspend fun usage(): CacheUsage
    suspend fun clearAll()
    suspend fun updateLimit(limit: CacheLimit)
}

/** Keeps cache state/refresh semantics common while platforms own their physical cache backend. */
class StorageBackedResourceCacheRepository(
    private val storage: ResourceCacheStorage,
) : ResourceCacheRepository {
    private val mutableUsage = MutableStateFlow(CacheUsage())
    override val usage: StateFlow<CacheUsage> = mutableUsage.asStateFlow()

    override suspend fun refreshUsage() {
        mutableUsage.value = storage.usage()
    }

    override suspend fun clearAll() {
        storage.clearAll()
        mutableUsage.value = CacheUsage()
    }

    override suspend fun updateLimit(limit: CacheLimit) {
        storage.updateLimit(limit)
        mutableUsage.value = storage.usage()
    }
}
