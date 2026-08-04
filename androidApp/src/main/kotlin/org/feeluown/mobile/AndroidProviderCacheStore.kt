package org.feeluown.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.feeluown.mobile.provider.core.network.PersistedProviderCacheEntry
import org.feeluown.mobile.provider.core.network.ProviderPersistentCache
import java.io.File

internal class AndroidProviderCacheStore(context: Context) : ProviderPersistentCache {
    private val file = File(context.applicationContext.filesDir, CACHE_FILE_NAME)
    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override suspend fun read(key: String): PersistedProviderCacheEntry? = withContext(Dispatchers.IO) {
        mutex.withLock { load()[key] }
    }

    override suspend fun write(key: String, entry: PersistedProviderCacheEntry) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val entries = load().toMutableMap()
                entries[key] = entry
                save(entries)
            }
        }
    }

    override suspend fun invalidate(prefix: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                save(load().filterKeys { !it.startsWith(prefix) })
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (file.exists()) file.delete()
            }
        }
    }

    private fun load(): Map<String, PersistedProviderCacheEntry> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, PersistedProviderCacheEntry>>(file.readText())
        }.getOrDefault(emptyMap())
    }

    private fun save(entries: Map<String, PersistedProviderCacheEntry>) {
        if (entries.isEmpty()) {
            if (file.exists()) file.delete()
            return
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.writeText(json.encodeToString(entries))
        check(temporary.renameTo(file) || run {
            file.delete()
            temporary.renameTo(file)
        }) { "无法写入 Provider HTTP 缓存" }
    }

    private companion object {
        const val CACHE_FILE_NAME = "provider_http_cache.json"
    }
}
