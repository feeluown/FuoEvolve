package org.feeluown.mobile

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import org.feeluown.mobile.provider.core.network.PersistedProviderCacheEntry
import org.feeluown.mobile.provider.core.network.ProviderPersistentCache
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
internal class IosProviderCacheStore : ProviderPersistentCache {
    private val fileSystem = FileSystem.SYSTEM
    private val file = cacheFile()
    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override suspend fun read(key: String): PersistedProviderCacheEntry? = withContext(Dispatchers.Default) {
        mutex.withLock { load()[key] }
    }

    override suspend fun write(key: String, entry: PersistedProviderCacheEntry) {
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val entries = load().toMutableMap()
                entries[key] = entry
                save(entries)
            }
        }
    }

    override suspend fun invalidate(prefix: String) {
        withContext(Dispatchers.Default) {
            mutex.withLock {
                save(load().filterKeys { !it.startsWith(prefix) })
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (fileSystem.exists(file)) fileSystem.delete(file)
            }
        }
    }

    private fun load(): Map<String, PersistedProviderCacheEntry> {
        if (!fileSystem.exists(file)) return emptyMap()
        return runCatching {
            fileSystem.read(file) { json.decodeFromString<Map<String, PersistedProviderCacheEntry>>(readUtf8()) }
        }.getOrDefault(emptyMap())
    }

    private fun save(entries: Map<String, PersistedProviderCacheEntry>) {
        if (entries.isEmpty()) {
            if (fileSystem.exists(file)) fileSystem.delete(file)
            return
        }
        file.parent?.let(fileSystem::createDirectories)
        fileSystem.write(file) { writeUtf8(json.encodeToString(entries)) }
    }

    private companion object {
        fun cacheFile(): Path {
            val directory = NSFileManager.defaultManager.URLForDirectory(
                directory = NSCachesDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null,
            )?.path ?: error("无法定位 iOS 文档目录")
            return "$directory/provider_http_cache.json".toPath()
        }
    }
}
