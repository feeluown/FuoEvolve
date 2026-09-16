package org.feeluown.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MEMORY_CACHE_BYTES = 24 * 1024 * 1024
private const val FAILED_CACHE_ENTRIES = 512
private const val FAILED_CACHE_RETRY_MS = 30_000L
private const val COVER_LOG_TAG = "CoverArt"

// Never log full URLs, local paths, exception messages or stack traces: they may contain credentials.
private fun coverLogSource(imageUrl: String): String {
    val uri = Uri.parse(imageUrl)
    return if (uri.scheme == "http" || uri.scheme == "https") {
        "host=${uri.host.orEmpty().ifBlank { "unknown" }}"
    } else {
        "scheme=${uri.scheme.orEmpty().ifBlank { "unknown" }}"
    }
}

@Composable
actual fun PlatformCoverArt(
    title: String,
    imageUrl: String?,
    modifier: Modifier,
    placeholder: CoverPlaceholder,
) {
    var isLaidOut by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = modifier.onGloballyPositioned { isLaidOut = true },
        contentAlignment = Alignment.Center,
    ) {
        val targetSizePx = coverTargetSizePx(maxWidth, maxHeight, LocalDensity.current)
        val bitmap = rememberPlatformCoverImage(imageUrl?.takeIf { isLaidOut }, targetSizePx)
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            CoverFallback(placeholder = placeholder, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
internal actual fun rememberPlatformCoverImage(imageUrl: String?, maxSizePx: Int): ImageBitmap? {
    val context = LocalContext.current
    val normalizedSizePx = normalizedCoverImageTargetSizePx(maxSizePx)
    val requestKey = imageUrl?.let { coverImageCacheKey(it, normalizedSizePx) }
    var image by remember(requestKey) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(requestKey) {
        image = imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            try {
                PlatformCoverImageCache.getOrLoad(requireNotNull(requestKey)) {
                    loadCover(context, url, normalizedSizePx)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                AppLogger.w(COVER_LOG_TAG, "Cover load failed ${coverLogSource(url)} stage=load error=${exception.javaClass.simpleName}")
                null
            }
        }
    }
    return image
}

private suspend fun loadCover(
    context: Context,
    imageUrl: String,
    maxSizePx: Int,
): ImageBitmap? = withContext(Dispatchers.IO) {
    val cacheKey = coverImageCacheKey(imageUrl, maxSizePx)
    CoverArtMemoryCache.get(cacheKey)?.let { return@withContext it }
    if (CoverArtMemoryCache.isFailed(cacheKey)) return@withContext null
    val uri = Uri.parse(imageUrl)
    val image = when (uri.scheme) {
        "fuo-cover" -> {
            val albumArt = uri.getQueryParameter("albumArt").orEmpty()
            val audio = uri.getQueryParameter("audio").orEmpty()
            albumArt.takeIf { it.isNotBlank() }?.let { loadDirectCover(context, it, maxSizePx) }
                ?: audio.takeIf { it.isNotBlank() }?.let { loadEmbeddedCover(context, it, maxSizePx) }
        }
        "content", "file" -> loadDirectCover(context, imageUrl, maxSizePx)
            ?: loadEmbeddedCover(context, imageUrl, maxSizePx)
        "http", "https" -> loadDirectCover(context, imageUrl, maxSizePx)
        else -> null
    }
    if (image != null) {
        CoverArtMemoryCache.put(cacheKey, image)
    } else {
        AppLogger.w(COVER_LOG_TAG, "Cover load failed ${coverLogSource(imageUrl)} stage=all-sources sizePx=$maxSizePx")
        CoverArtMemoryCache.markFailed(cacheKey)
    }
    image
}

private fun loadDirectCover(context: Context, imageUrl: String, maxSizePx: Int): ImageBitmap? {
    val cacheKey = coverImageCacheKey(imageUrl, maxSizePx)
    CoverArtMemoryCache.get(cacheKey)?.let { return it }
    if (CoverArtMemoryCache.isFailed(cacheKey)) return null
    val requestUrls = coverImageRequestUrls(imageUrl, maxSizePx)
    val uri = Uri.parse(requestUrls.first())
    val image = try {
        when (uri.scheme) {
            "content" -> decodeSampledStream({ context.contentResolver.openInputStream(uri) }, maxSizePx)?.asImageBitmap()
            "file" -> uri.path?.let { decodeSampledFile(File(it), maxSizePx) }?.asImageBitmap()
            "http", "https" -> requestUrls.firstNotNullOfOrNull { requestUrl ->
                loadCachedRemoteCover(context, requestUrl, maxSizePx)
            }
            else -> null
        }
    } catch (exception: Exception) {
        AppLogger.w(COVER_LOG_TAG, "Cover load failed ${coverLogSource(imageUrl)} stage=direct error=${exception.javaClass.simpleName}")
        null
    }
    if (image != null) {
        CoverArtMemoryCache.put(cacheKey, image)
    } else {
        if (uri.scheme == "http" || uri.scheme == "https") {
            AppLogger.w(COVER_LOG_TAG, "All cover URLs failed ${coverLogSource(imageUrl)} stage=remote attempts=${requestUrls.size}")
        } else {
            AppLogger.w(COVER_LOG_TAG, "Cover decode failed ${coverLogSource(imageUrl)} stage=direct")
        }
        CoverArtMemoryCache.markFailed(cacheKey)
    }
    return image
}

private fun loadCachedRemoteCover(context: Context, imageUrl: String, maxSizePx: Int): ImageBitmap? {
    val cached = AndroidResourceCache.cachedImage(context, imageUrl)
    if (cached != null && cached.exists() && cached.length() > 0L) {
        val decoded = try {
            decodeSampledFile(cached, maxSizePx)?.asImageBitmap()
        } catch (exception: Exception) {
            AppLogger.w(COVER_LOG_TAG, "Cover decode failed ${coverLogSource(imageUrl)} stage=disk-cache error=${exception.javaClass.simpleName}")
            null
        }
        if (decoded != null) return decoded
        AppLogger.w(COVER_LOG_TAG, "Cover decode failed ${coverLogSource(imageUrl)} stage=disk-cache bytes=${cached.length()}")
        if (!cached.delete()) {
            AppLogger.w(COVER_LOG_TAG, "Invalid cover cache removal failed ${coverLogSource(imageUrl)} stage=disk-cache")
        }
    }
    return loadRemoteCoverWithoutDiskCache(imageUrl, maxSizePx)
}

private fun loadEmbeddedCover(context: Context, imageUrl: String, maxSizePx: Int): ImageBitmap? {
    val uri = Uri.parse(imageUrl)
    val retriever = MediaMetadataRetriever()
    return try {
        when (uri.scheme) {
            "content" -> retriever.setDataSource(context, uri)
            "file" -> retriever.setDataSource(requireNotNull(uri.path))
            else -> return null
        }
        val bytes = retriever.embeddedPicture
        if (bytes == null) {
            AppLogger.d(COVER_LOG_TAG, "No embedded artwork ${coverLogSource(imageUrl)}")
            return null
        }
        decodeSampledByteArray(bytes, maxSizePx)?.asImageBitmap().also { image ->
            if (image == null) AppLogger.w(COVER_LOG_TAG, "Cover decode failed ${coverLogSource(imageUrl)} stage=embedded bytes=${bytes.size}")
        }
    } catch (exception: Exception) {
        AppLogger.w(COVER_LOG_TAG, "Embedded cover load failed ${coverLogSource(imageUrl)} error=${exception.javaClass.simpleName}")
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun loadRemoteCoverWithoutDiskCache(imageUrl: String, maxSizePx: Int): ImageBitmap? {
    val source = coverLogSource(imageUrl)
    val connection = try {
        URL(imageUrl).openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 20_000
        }
    } catch (exception: Exception) {
        AppLogger.w(COVER_LOG_TAG, "Cover request failed $source stage=direct-connect error=${exception.javaClass.simpleName}")
        return null
    }
    return try {
        if (connection is HttpURLConnection) {
            val status = connection.responseCode
            if (status !in 200..299) {
                AppLogger.w(COVER_LOG_TAG, "Cover request failed $source stage=direct-download httpStatus=$status")
                return null
            }
        }
        val bytes = connection.getInputStream().use { it.readBytes() }
        if (bytes.isEmpty()) {
            AppLogger.w(COVER_LOG_TAG, "Cover response is empty $source stage=direct-download")
            return null
        }
        decodeSampledByteArray(bytes, maxSizePx)?.asImageBitmap().also { image ->
            if (image == null) AppLogger.w(COVER_LOG_TAG, "Cover decode failed $source stage=direct-download bytes=${bytes.size}")
        }
    } catch (exception: Exception) {
        AppLogger.w(COVER_LOG_TAG, "Cover request failed $source stage=direct-download error=${exception.javaClass.simpleName}")
        null
    } finally {
        (connection as? HttpURLConnection)?.disconnect()
    }
}

private fun decodeSampledFile(file: File, maxSizePx: Int): Bitmap? {
    if (!file.isFile) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (!bounds.hasSize()) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = bounds.inSampleSize(maxSizePx)
    }
    return BitmapFactory.decodeFile(file.path, options)
}

private fun decodeSampledStream(openInput: () -> InputStream?, maxSizePx: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openInput()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
    if (!bounds.hasSize()) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = bounds.inSampleSize(maxSizePx)
    }
    return openInput()?.use { BitmapFactory.decodeStream(it, null, options) }
}

private fun decodeSampledByteArray(bytes: ByteArray, maxSizePx: Int): Bitmap? {
    if (bytes.isEmpty()) return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (!bounds.hasSize()) return null
    val options = BitmapFactory.Options().apply {
        inSampleSize = bounds.inSampleSize(maxSizePx)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
}

private fun BitmapFactory.Options.hasSize(): Boolean = outWidth > 0 && outHeight > 0

private fun BitmapFactory.Options.inSampleSize(maxSizePx: Int): Int {
    var sampleSize = 1
    while (outWidth / sampleSize > maxSizePx || outHeight / sampleSize > maxSizePx) {
        sampleSize *= 2
    }
    return sampleSize
}

private object CoverArtMemoryCache {
    private val images = object : LruCache<String, ImageBitmap>(MEMORY_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int {
            return (value.width.toLong() * value.height.toLong() * 4L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }
    }
    private val failed = object : LruCache<String, Long>(FAILED_CACHE_ENTRIES) {
        override fun sizeOf(key: String, value: Long): Int = 1
    }

    @Synchronized
    fun get(key: String): ImageBitmap? = images.get(key)

    @Synchronized
    fun put(key: String, image: ImageBitmap) {
        failed.remove(key)
        images.put(key, image)
    }

    @Synchronized
    fun isFailed(key: String): Boolean {
        val failedAt = failed.get(key) ?: return false
        if (SystemClock.elapsedRealtime() - failedAt < FAILED_CACHE_RETRY_MS) return true
        failed.remove(key)
        return false
    }

    @Synchronized
    fun markFailed(key: String) {
        failed.put(key, SystemClock.elapsedRealtime())
    }
}

@Composable
private fun CoverFallback(placeholder: CoverPlaceholder, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        BoxWithConstraints(contentAlignment = Alignment.Center) {
            val containerSize = minOf(maxWidth, maxHeight)
            Icon(
                imageVector = when (placeholder) {
                    CoverPlaceholder.Song -> Icons.Filled.MusicNote
                    CoverPlaceholder.Album -> Icons.Filled.Album
                    CoverPlaceholder.Artist -> Icons.Filled.Mic
                    CoverPlaceholder.Playlist -> Icons.AutoMirrored.Filled.QueueMusic
                    CoverPlaceholder.DailyRecommendation -> Icons.Filled.CalendarMonth
                },
                contentDescription = null,
                modifier = Modifier.size(containerSize * 0.45f),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
