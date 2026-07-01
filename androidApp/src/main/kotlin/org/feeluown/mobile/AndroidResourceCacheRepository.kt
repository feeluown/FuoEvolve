package org.feeluown.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

class AndroidResourceCacheRepository(
    context: Context,
) : ResourceCacheRepository {
    private val appContext = context.applicationContext
    private val mutableUsage = MutableStateFlow(CacheUsage())

    override val usage: StateFlow<CacheUsage> = mutableUsage.asStateFlow()

    override suspend fun refreshUsage() {
        mutableUsage.value = withContext(Dispatchers.IO) {
            AndroidResourceCache.usage(appContext)
        }
    }

    override suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            AndroidResourceCache.clearAll(appContext)
            mutableUsage.value = CacheUsage()
        }
    }

    override suspend fun updateLimit(limit: CacheLimit) {
        withContext(Dispatchers.IO) {
            AndroidResourceCache.updateLimit(appContext, limit)
            mutableUsage.value = AndroidResourceCache.usage(appContext)
        }
    }
}
