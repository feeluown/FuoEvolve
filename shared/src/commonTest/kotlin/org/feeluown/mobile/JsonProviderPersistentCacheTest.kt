package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.feeluown.mobile.provider.core.network.PersistedProviderCacheEntry

class JsonProviderPersistentCacheTest {
    @Test
    fun persistsReadsAndInvalidatesByPrefix() = runTest {
        val storage = MemoryProviderCacheStorage()
        val cache = JsonProviderPersistentCache(storage)
        val first = PersistedProviderCacheEntry("first", 100L)
        val second = PersistedProviderCacheEntry("second", 200L)

        cache.write("catalog:one", first)
        cache.write("auth:two", second)

        assertEquals(first, cache.read("catalog:one"))
        assertEquals(second, cache.read("auth:two"))

        cache.invalidate("catalog:")

        assertNull(cache.read("catalog:one"))
        assertEquals(second, cache.read("auth:two"))
    }

    @Test
    fun corruptedSnapshotIsTreatedAsEmptyAndCanRecover() = runTest {
        val storage = MemoryProviderCacheStorage("not-json")
        val cache = JsonProviderPersistentCache(storage)
        assertNull(cache.read("missing"))

        val entry = PersistedProviderCacheEntry("recovered", 300L)
        cache.write("key", entry)

        assertEquals(entry, JsonProviderPersistentCache(storage).read("key"))
    }

    @Test
    fun oldestEntriesAreEvictedWhenCapacityIsExceeded() = runTest {
        val storage = MemoryProviderCacheStorage()
        val cache = JsonProviderPersistentCache(storage, maxEntries = 2)

        cache.write("oldest", PersistedProviderCacheEntry("one", 100L))
        cache.write("newest", PersistedProviderCacheEntry("two", 300L))
        cache.write("middle", PersistedProviderCacheEntry("three", 200L))

        assertNull(cache.read("oldest"))
        assertEquals("two", cache.read("newest")?.value)
        assertEquals("three", cache.read("middle")?.value)

        val reopened = JsonProviderPersistentCache(storage, maxEntries = 2)
        assertNull(reopened.read("oldest"))
        assertEquals("two", reopened.read("newest")?.value)
        assertEquals("three", reopened.read("middle")?.value)
    }
}

private class MemoryProviderCacheStorage(
    private var content: String? = null,
) : ProviderCacheSnapshotStorage {
    override suspend fun readText(): String? = content
    override suspend fun writeText(content: String) {
        this.content = content
    }
    override suspend fun delete() {
        content = null
    }
}
