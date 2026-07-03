package org.feeluown.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSUserDomainMask

class IosResourceCacheRepository : ResourceCacheRepository {
    private val mutableUsage = MutableStateFlow(CacheUsage())
    private var limit = CacheLimit(
        audioMaxBytes = DEFAULT_AUDIO_CACHE_LIMIT_MB.toLong() * 1024L * 1024L,
        imageMaxBytes = DEFAULT_IMAGE_CACHE_LIMIT_MB.toLong() * 1024L * 1024L,
    )

    override val usage: StateFlow<CacheUsage> = mutableUsage.asStateFlow()

    override suspend fun refreshUsage() {
        mutableUsage.value = withContext(Dispatchers.Default) {
            CacheUsage(
                audioBytes = cacheDirectory(AUDIO_CACHE).directorySize(),
                imageBytes = cacheDirectory(IMAGE_CACHE).directorySize(),
            )
        }
    }

    override suspend fun clearAll() {
        withContext(Dispatchers.Default) {
            listOf(AUDIO_CACHE, IMAGE_CACHE).forEach { cacheDirectory(it).deleteRecursively() }
            mutableUsage.value = CacheUsage()
        }
    }

    override suspend fun updateLimit(limit: CacheLimit) {
        this.limit = limit
        refreshUsage()
    }

    internal fun audioCacheDirectory(): String = cacheDirectory(AUDIO_CACHE).ensureDirectory()

    internal fun imageCacheDirectory(): String = cacheDirectory(IMAGE_CACHE).ensureDirectory()

    private companion object {
        private const val AUDIO_CACHE = "audio"
        private const val IMAGE_CACHE = "images"
    }
}

internal fun cacheDirectory(name: String): String {
    val root = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String
        ?: NSTemporaryDirectory()
    return "$root/FuoEvolve/$name"
}

internal fun String.ensureDirectory(): String {
    NSFileManager.defaultManager.createDirectoryAtPath(this, withIntermediateDirectories = true, attributes = null, error = null)
    return this
}

internal fun String.deleteRecursively() {
    NSFileManager.defaultManager.removeItemAtPath(this, error = null)
}

internal fun String.directorySize(): Long {
    val manager = NSFileManager.defaultManager
    val enumerator = manager.enumeratorAtPath(this) ?: return 0
    var total = 0L
    while (true) {
        val next = enumerator.nextObject() as? String ?: break
        val path = "$this/$next"
        val attrs = manager.attributesOfItemAtPath(path, error = null) ?: continue
        total += (attrs["NSFileSize"] as? Number)?.toLong() ?: 0L
    }
    return total
}

internal fun NSTemporaryDirectory(): String = platform.Foundation.NSTemporaryDirectory()
