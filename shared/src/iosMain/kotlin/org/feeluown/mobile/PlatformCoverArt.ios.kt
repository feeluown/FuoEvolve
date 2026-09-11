package org.feeluown.mobile

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
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.impl.use
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithURL
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
            Image(bitmap, title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {
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
    }
}

@Composable
internal actual fun rememberPlatformCoverImage(imageUrl: String?, maxSizePx: Int): ImageBitmap? {
    val normalizedSizePx = normalizedCoverImageTargetSizePx(maxSizePx)
    val requestKey = imageUrl?.let { coverImageCacheKey(it, normalizedSizePx) }
    var image by remember(requestKey) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(requestKey) {
        image = imageUrl?.takeIf { it.isNotBlank() }?.let {
            runCatching {
                PlatformCoverImageCache.getOrLoad(requireNotNull(requestKey)) {
                    loadImage(it, normalizedSizePx)
                }
            }.getOrNull()
        }
    }
    return image
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun loadImage(imageUrl: String, maxSizePx: Int): ImageBitmap? = withContext(Dispatchers.Default) {
    val resolvedUrl = when {
        imageUrl.startsWith("fuo-cover:") -> {
            val query = imageUrl.substringAfter('?', "")
            query.split('&').firstNotNullOfOrNull { entry ->
                val parts = entry.split('=', limit = 2)
                parts.getOrNull(1)?.takeIf { parts.firstOrNull() == "albumArt" && it.isNotBlank() }
            }
        }
        else -> imageUrl
    } ?: return@withContext null
    coverImageRequestUrls(resolvedUrl, maxSizePx).firstNotNullOfOrNull { requestUrl ->
        val url = NSURL.URLWithString(requestUrl) ?: return@firstNotNullOfOrNull null
        val data = if (url.scheme == "file") {
            url.path?.let { NSFileManager.defaultManager.contentsAtPath(it) }
        } else {
            fetchData(url)
        } ?: return@firstNotNullOfOrNull null
        val bytes = data.bytes?.reinterpret<ByteVar>()?.readBytes(data.length.toInt())
            ?: return@firstNotNullOfOrNull null
        decodeCoverImage(bytes, maxSizePx)
    }
}

private fun decodeCoverImage(bytes: ByteArray, maxSizePx: Int): ImageBitmap? = runCatching {
    if (bytes.isEmpty()) return@runCatching null
    Data.makeFromBytes(bytes).use { encodedData ->
        Codec.makeFromData(encodedData).use { codec ->
            val sourceInfo = codec.imageInfo
            if (sourceInfo.width <= 0 || sourceInfo.height <= 0) return@use null
            val scale = minOf(
                1f,
                maxSizePx.toFloat() / sourceInfo.width,
                maxSizePx.toFloat() / sourceInfo.height,
            )
            val targetWidth = (sourceInfo.width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (sourceInfo.height * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap()
            bitmap.use {
                check(it.allocPixels(sourceInfo.withWidthHeight(targetWidth, targetHeight)))
                codec.readPixels(it)
                Image.makeFromBitmap(it).toComposeImageBitmap()
            }
        }
    }
}.getOrNull()

private suspend fun fetchData(url: NSURL): NSData? = suspendCoroutine { continuation ->
    NSURLSession.sharedSession.dataTaskWithURL(url) { data, _, _ ->
        continuation.resume(data)
    }.resume()
}
