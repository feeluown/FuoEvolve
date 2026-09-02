package org.feeluown.mobile

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.feeluown.mobile.provider.core.network.ProviderPersistentCache

internal class AndroidProviderCacheStore(context: Context) : ProviderPersistentCache by JsonProviderPersistentCache(
    AndroidProviderCacheSnapshotStorage(
        File(context.applicationContext.filesDir, CACHE_FILE_NAME),
    ),
)

private class AndroidProviderCacheSnapshotStorage(
    private val file: File,
) : ProviderCacheSnapshotStorage {
    override suspend fun readText(): String? = withContext(Dispatchers.IO) {
        file.takeIf(File::isFile)?.readText()
    }

    override suspend fun writeText(content: String) {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(content)
            check(temporary.renameTo(file) || run {
                file.delete()
                temporary.renameTo(file)
            }) { "无法写入 Provider HTTP 缓存" }
        }
    }

    override suspend fun delete() {
        withContext(Dispatchers.IO) {
            if (file.exists()) file.delete()
        }
    }
}

private const val CACHE_FILE_NAME = "provider_http_cache.json"
