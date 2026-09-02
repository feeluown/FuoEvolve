package org.feeluown.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidResourceCacheRepository(
    context: Context,
) : ResourceCacheRepository by StorageBackedResourceCacheRepository(
    AndroidResourceCacheStorage(context.applicationContext),
)

private class AndroidResourceCacheStorage(
    private val context: Context,
) : ResourceCacheStorage {
    override suspend fun usage(): CacheUsage = withContext(Dispatchers.IO) {
        AndroidResourceCache.usage(context)
    }

    override suspend fun clearAll() {
        withContext(Dispatchers.IO) { AndroidResourceCache.clearAll(context) }
    }

    override suspend fun updateLimit(limit: CacheLimit) {
        withContext(Dispatchers.IO) { AndroidResourceCache.updateLimit(context, limit) }
    }
}
