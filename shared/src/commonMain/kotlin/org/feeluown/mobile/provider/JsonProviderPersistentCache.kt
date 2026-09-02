package org.feeluown.mobile

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.feeluown.mobile.provider.core.network.PersistedProviderCacheEntry
import org.feeluown.mobile.provider.core.network.ProviderPersistentCache

/** Platform storage boundary for the provider HTTP cache snapshot. */
interface ProviderCacheSnapshotStorage {
    suspend fun readText(): String?
    suspend fun writeText(content: String)
    suspend fun delete()
}

/**
 * Platform-neutral provider cache implementation.
 *
 * Cache serialization, bounded eviction, prefix invalidation and corruption tolerance belong to
 * shared runtime semantics. Platforms only decide where and how the snapshot is stored atomically.
 */
class JsonProviderPersistentCache(
    private val storage: ProviderCacheSnapshotStorage,
    private val maxEntries: Int = DEFAULT_PROVIDER_PERSISTENT_CACHE_ENTRIES,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
) : ProviderPersistentCache {
    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private val mutex = Mutex()

    override suspend fun read(key: String): PersistedProviderCacheEntry? =
        mutex.withLock { load()[key] }

    override suspend fun write(key: String, entry: PersistedProviderCacheEntry) {
        mutex.withLock {
            val entries = load().toMutableMap().apply { put(key, entry) }
            save(entries.trimToNewest(maxEntries))
        }
    }

    override suspend fun invalidate(prefix: String) {
        mutex.withLock {
            val current = load()
            val filtered = current.filterKeys { !it.startsWith(prefix) }
            if (filtered.size != current.size) save(filtered)
        }
    }

    override suspend fun clear() {
        mutex.withLock { storage.delete() }
    }

    private suspend fun load(): Map<String, PersistedProviderCacheEntry> =
        storage.readText()
            ?.takeIf(String::isNotBlank)
            ?.let { raw -> runCatching { json.decodeFromString<Map<String, PersistedProviderCacheEntry>>(raw) }.getOrNull() }
            ?: emptyMap()

    private suspend fun save(entries: Map<String, PersistedProviderCacheEntry>) {
        if (entries.isEmpty()) {
            storage.delete()
        } else {
            storage.writeText(json.encodeToString(entries))
        }
    }
}

private fun Map<String, PersistedProviderCacheEntry>.trimToNewest(
    maxEntries: Int,
): Map<String, PersistedProviderCacheEntry> {
    if (size <= maxEntries) return this
    return entries
        .sortedWith(
            compareByDescending<Map.Entry<String, PersistedProviderCacheEntry>> { it.value.storedAtMillis }
                .thenBy { it.key },
        )
        .take(maxEntries)
        .associate { it.toPair() }
}

private const val DEFAULT_PROVIDER_PERSISTENT_CACHE_ENTRIES = 256
