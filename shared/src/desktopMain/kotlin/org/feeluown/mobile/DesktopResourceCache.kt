package org.feeluown.mobile

import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Comparator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun createDesktopResourceCacheRepository(): ResourceCacheRepository =
    StorageBackedResourceCacheRepository(DesktopResourceCache)

/** Desktop filesystem adapter for resource caching. Shared code owns repository state semantics. */
internal object DesktopResourceCache : ResourceCacheStorage {
    private val root: Path
        get() = DesktopAppDirectories.cache().resolve("resources")
    private val imageDirectory: Path
        get() = root.resolve("images")

    private val lock = Any()
    private var imageLimitBytes = DEFAULT_IMAGE_CACHE_LIMIT_MB.toLong() * 1024L * 1024L

    override suspend fun usage(): CacheUsage = withContext(Dispatchers.IO) {
        synchronized(lock) {
            CacheUsage(
                // libmpv owns its streaming buffer; do not report a fake Media3-style disk audio cache.
                audioBytes = 0L,
                imageBytes = directorySize(imageDirectory),
            )
        }
    }

    override suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                deleteRecursively(root)
                Files.createDirectories(root)
            }
        }
    }

    override suspend fun updateLimit(limit: CacheLimit) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                imageLimitBytes = limit.imageMaxBytes.coerceAtLeast(0L)
                trimImagesLocked()
            }
        }
    }

    fun cachedRemoteImage(url: String): Path? = synchronized(lock) {
        if (!isHttpUrl(url) || imageLimitBytes <= 0L) return null
        Files.createDirectories(imageDirectory)
        val target = imageDirectory.resolve("${sha256(url)}.img")
        if (Files.isRegularFile(target) && Files.size(target) > 0L) {
            Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
            return target
        }

        val temporary = Files.createTempFile(imageDirectory, ".cover-", ".tmp")
        return try {
            URL(url).openConnection().run {
                connectTimeout = 15_000
                readTimeout = 20_000
                getInputStream().use { input ->
                    Files.newOutputStream(temporary).use { output -> input.copyTo(output) }
                }
            }
            if (Files.size(temporary) <= 0L) {
                null
            } else {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
                Files.setLastModifiedTime(target, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()))
                trimImagesLocked()
                target.takeIf { Files.isRegularFile(it) }
            }
        } catch (_: Throwable) {
            null
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun trimImagesLocked() {
        if (!Files.isDirectory(imageDirectory)) return
        if (imageLimitBytes <= 0L) {
            deleteRecursively(imageDirectory)
            return
        }
        val files = Files.list(imageDirectory).use { stream ->
            stream.filter { Files.isRegularFile(it) }.toList()
        }
        var total = files.sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
        files.sortedBy { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrDefault(0L) }
            .forEach { file ->
                if (total <= imageLimitBytes) return
                val size = runCatching { Files.size(file) }.getOrDefault(0L)
                if (Files.deleteIfExists(file)) total -= size
            }
    }

    private fun directorySize(directory: Path): Long {
        if (!Files.isDirectory(directory)) return 0L
        return Files.walk(directory).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .mapToLong { runCatching { Files.size(it) }.getOrDefault(0L) }
                .sum()
        }
    }

    private fun deleteRecursively(path: Path) {
        if (!Files.exists(path)) return
        Files.walk(path).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        URI(value).scheme?.lowercase() in setOf("http", "https")
    }.getOrDefault(false)

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.encodeToByteArray())
        .joinToString("") { byte -> "%02x".format(byte) }
}
