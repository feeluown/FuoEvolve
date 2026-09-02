package org.feeluown.mobile

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.feeluown.mobile.provider.core.network.ProviderPersistentCache

internal fun createDesktopProviderCacheStore(): ProviderPersistentCache = JsonProviderPersistentCache(
    DesktopProviderCacheSnapshotStorage(
        DesktopAppDirectories.cache().resolve("provider_http_cache.json"),
    ),
)

private class DesktopProviderCacheSnapshotStorage(
    private val file: Path,
) : ProviderCacheSnapshotStorage {
    override suspend fun readText(): String? = withContext(Dispatchers.IO) {
        file.takeIf { Files.isRegularFile(it) }?.let { Files.readString(it, StandardCharsets.UTF_8) }
    }

    override suspend fun writeText(content: String) {
        withContext(Dispatchers.IO) {
            val directory = requireNotNull(file.parent)
            Files.createDirectories(directory)
            val temporary = Files.createTempFile(directory, ".provider-cache-", ".tmp")
            try {
                Files.writeString(temporary, content, StandardCharsets.UTF_8)
                try {
                    Files.move(
                        temporary,
                        file,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }
    }

    override suspend fun delete() {
        withContext(Dispatchers.IO) { Files.deleteIfExists(file) }
    }
}
