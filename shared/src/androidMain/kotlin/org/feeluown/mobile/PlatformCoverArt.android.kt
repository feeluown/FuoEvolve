package org.feeluown.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.net.URL

private const val MEMORY_CACHE_BYTES = 24 * 1024 * 1024
private const val FAILED_CACHE_ENTRIES = 512

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
        image = imageUrl?.takeIf { it.isNotBlank() }?.let {
            runCatching {
                PlatformCoverImageCache.getOrLoad(requireNotNull(requestKey)) {
                    loadCover(context, it, normalizedSizePx)
                }
            }.getOrNull()
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
    val image = when (uri.scheme) {
        "content" -> decodeSampledStream({ context.contentResolver.openInputStream(uri) }, maxSizePx)?.asImageBitmap()
        "file" -> uri.path?.let { decodeSampledFile(File(it), maxSizePx) }?.asImageBitmap()
        "http", "https" -> requestUrls.firstNotNullOfOrNull { requestUrl ->
            loadCachedRemoteCover(context, requestUrl, maxSizePx)
        }
        else -> null
    }
    if (image != null) {
        CoverArtMemoryCache.put(cacheKey, image)
    } else {
        CoverArtMemoryCache.markFailed(cacheKey)
    }
    return image
}

private fun loadCachedRemoteCover(context: Context, imageUrl: String, maxSizePx: Int) =
    AndroidResourceCache.cachedImage(context, imageUrl)
        ?.takeIf { it.exists() && it.length() > 0L }
        ?.let { decodeSampledFile(it, maxSizePx)?.asImageBitmap() }
        ?: loadRemoteCoverWithoutDiskCache(imageUrl, maxSizePx)

private fun loadEmbeddedCover(context: Context, imageUrl: String, maxSizePx: Int): ImageBitmap? {
    val uri = Uri.parse(imageUrl)
    val retriever = MediaMetadataRetriever()
    return try {
        when (uri.scheme) {
            "content" -> retriever.setDataSource(context, uri)
            "file" -> retriever.setDataSource(requireNotNull(uri.path))
            else -> return null
        }
        val bytes = retriever.embeddedPicture ?: return null
        decodeSampledByteArray(bytes, maxSizePx)?.asImageBitmap()
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
    }
}

private fun loadRemoteCoverWithoutDiskCache(imageUrl: String, maxSizePx: Int): ImageBitmap? {
    val bytes = runCatching {
        URL(imageUrl).openConnection().run {
            connectTimeout = 15_000
            readTimeout = 20_000
            getInputStream().use { it.readBytes() }
        }
    }.getOrNull() ?: return null
    return decodeSampledByteArray(bytes, maxSizePx)?.asImageBitmap()
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
    if (!bounds.hasSize()) {
        return null
    }
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
    private val failed = object : LruCache<String, Boolean>(FAILED_CACHE_ENTRIES) {
        override fun sizeOf(key: String, value: Boolean): Int = 1
    }

    @Synchronized
    fun get(key: String): ImageBitmap? = images.get(key)

    @Synchronized
    fun put(key: String, image: ImageBitmap) {
        failed.remove(key)
        images.put(key, image)
    }

    @Synchronized
    fun isFailed(key: String): Boolean = failed.get(key) == true

    @Synchronized
    fun markFailed(key: String) {
        failed.put(key, true)
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
