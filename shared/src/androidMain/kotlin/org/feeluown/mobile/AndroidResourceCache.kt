package org.feeluown.mobile

import android.content.Context
import android.net.Uri
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.net.URL
import java.security.MessageDigest

object AndroidResourceCache {
    private const val PREFS_NAME = "fuo_resource_cache"
    private const val KEY_AUDIO_LIMIT_BYTES = "audio_limit_bytes"
    private const val KEY_IMAGE_LIMIT_BYTES = "image_limit_bytes"
    private const val CACHE_ROOT = "fuo_provider_cache"
    private const val AUDIO_DIR = "audio"
    private const val IMAGE_DIR = "images"
    private const val IMAGE_EXTENSION = ".img"

    private val lock = Any()
    private var audioCache: SimpleCache? = null
    private var audioCacheLimitBytes: Long = 0

    fun audioCache(context: Context): SimpleCache {
        val limit = limit(context).audioMaxBytes
        synchronized(lock) {
            audioCache?.takeIf { audioCacheLimitBytes == limit }?.let { return it }
            audioCache?.release()
            audioCacheLimitBytes = limit
            return SimpleCache(
                audioDir(context).apply { mkdirs() },
                LeastRecentlyUsedCacheEvictor(limit),
            ).also { audioCache = it }
        }
    }

    fun usage(context: Context): CacheUsage {
        val audioBytes = synchronized(lock) {
            audioCache?.cacheSpace ?: audioDir(context).sizeBytes()
        }
        return CacheUsage(
            audioBytes = audioBytes,
            imageBytes = imageDir(context).sizeBytes(),
        )
    }

    fun updateLimit(context: Context, limit: CacheLimit) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_AUDIO_LIMIT_BYTES, limit.audioMaxBytes)
            .putLong(KEY_IMAGE_LIMIT_BYTES, limit.imageMaxBytes)
            .apply()
        synchronized(lock) {
            if (audioCache != null && audioCacheLimitBytes != limit.audioMaxBytes) {
                audioCache?.release()
                audioCache = null
                audioCacheLimitBytes = 0
            }
        }
        trimImages(context)
    }

    fun clearAll(context: Context) {
        synchronized(lock) {
            audioCache?.release()
            audioCache = null
            audioCacheLimitBytes = 0
        }
        audioDir(context).deleteRecursively()
        imageDir(context).deleteRecursively()
        rootDir(context).mkdirs()
    }

    fun cachedImage(context: Context, imageUrl: String): File? {
        val uri = Uri.parse(imageUrl)
        if (uri.scheme !in setOf("http", "https")) return null
        val target = File(imageDir(context).apply { mkdirs() }, "${sha256(imageUrl)}$IMAGE_EXTENSION")
        if (target.exists() && target.length() > 0L) {
            target.setLastModified(System.currentTimeMillis())
            return target
        }
        val temp = File(target.parentFile, "${target.name}.tmp")
        return runCatching {
            URL(imageUrl).openConnection().run {
                connectTimeout = 15_000
                readTimeout = 20_000
                getInputStream().use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (temp.length() <= 0L) {
                temp.delete()
                null
            } else {
                if (target.exists()) target.delete()
                if (temp.renameTo(target)) {
                    target.setLastModified(System.currentTimeMillis())
                    trimImages(context)
                    target
                } else {
                    temp.delete()
                    null
                }
            }
        }.getOrNull()
    }

    private fun trimImages(context: Context) {
        val maxBytes = limit(context).imageMaxBytes
        if (maxBytes <= 0L) {
            imageDir(context).deleteRecursively()
            return
        }
        val files = imageDir(context).listFiles()?.filter { it.isFile }.orEmpty()
        var total = files.sumOf { it.length() }
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (total <= maxBytes) return
            val size = file.length()
            if (file.delete()) total -= size
        }
    }

    private fun limit(context: Context): CacheLimit {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return CacheLimit(
            audioMaxBytes = preferences.getLong(
                KEY_AUDIO_LIMIT_BYTES,
                DEFAULT_AUDIO_CACHE_LIMIT_MB.toLong() * 1024L * 1024L,
            ),
            imageMaxBytes = preferences.getLong(
                KEY_IMAGE_LIMIT_BYTES,
                DEFAULT_IMAGE_CACHE_LIMIT_MB.toLong() * 1024L * 1024L,
            ),
        )
    }

    private fun rootDir(context: Context): File = File(context.cacheDir, CACHE_ROOT)

    private fun audioDir(context: Context): File = File(rootDir(context), AUDIO_DIR)

    private fun imageDir(context: Context): File = File(rootDir(context), IMAGE_DIR)

    private fun File.sizeBytes(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        return walkTopDown()
            .filter { it.isFile }
            .sumOf { it.length() }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray())
        return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
