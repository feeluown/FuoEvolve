package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class StorageBackedResourceCacheRepositoryTest {
    @Test
    fun refreshUpdateAndClearKeepRepositoryStateInSync() = runTest {
        val storage = FakeResourceCacheStorage(CacheUsage(audioBytes = 10L, imageBytes = 20L))
        val repository = StorageBackedResourceCacheRepository(storage)

        repository.refreshUsage()
        assertEquals(CacheUsage(10L, 20L), repository.usage.value)

        storage.nextUsage = CacheUsage(audioBytes = 30L, imageBytes = 40L)
        val limit = CacheLimit(audioMaxBytes = 100L, imageMaxBytes = 200L)
        repository.updateLimit(limit)
        assertEquals(limit, storage.updatedLimit)
        assertEquals(CacheUsage(30L, 40L), repository.usage.value)

        repository.clearAll()
        assertEquals(1, storage.clearCount)
        assertEquals(CacheUsage(), repository.usage.value)
    }
}

private class FakeResourceCacheStorage(
    var nextUsage: CacheUsage,
) : ResourceCacheStorage {
    var updatedLimit: CacheLimit? = null
    var clearCount = 0

    override suspend fun usage(): CacheUsage = nextUsage

    override suspend fun clearAll() {
        clearCount += 1
    }

    override suspend fun updateLimit(limit: CacheLimit) {
        updatedLimit = limit
    }
}
