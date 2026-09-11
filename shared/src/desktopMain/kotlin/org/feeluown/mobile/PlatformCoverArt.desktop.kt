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
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image as SkiaImage
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SurfaceProps
import org.jetbrains.skia.impl.use

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
        image = imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            runCatching {
                PlatformCoverImageCache.getOrLoad(requireNotNull(requestKey)) {
                    loadDesktopCover(url, normalizedSizePx)
                }
            }.getOrNull()
        }
    }
    return image
}

private suspend fun loadDesktopCover(imageUrl: String, maxSizePx: Int): ImageBitmap? = withContext(Dispatchers.IO) {
    val resolvedUrl = when {
        imageUrl.startsWith("fuo-cover:") -> imageUrl.substringAfter('?', "")
            .split('&')
            .firstNotNullOfOrNull { entry ->
                val parts = entry.split('=', limit = 2)
                parts.getOrNull(1)?.takeIf { parts.firstOrNull() == "albumArt" && it.isNotBlank() }
            }
        else -> imageUrl
    } ?: return@withContext null

    val bytes = if (resolvedUrl.startsWith("file:")) {
        Files.readAllBytes(Paths.get(URI(resolvedUrl)))
    } else {
        coverImageRequestUrls(resolvedUrl, maxSizePx).firstNotNullOfOrNull { requestUrl ->
            runCatching {
                DesktopResourceCache.cachedRemoteImage(requestUrl)
                    ?.let(Files::readAllBytes)
                    ?: URL(requestUrl).openStream().use { it.readBytes() }
            }.getOrNull()
        }
    } ?: return@withContext null
    decodeDesktopCover(bytes, maxSizePx)
}

internal fun decodeDesktopCover(bytes: ByteArray, maxSizePx: Int): ImageBitmap? = runCatching {
    if (bytes.isEmpty()) return@runCatching null
    SkiaImage.makeFromEncoded(bytes).use { image ->
        val sourceInfo = image.imageInfo
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
            check(
                it.allocPixels(
                    ImageInfo.makeN32(targetWidth, targetHeight, ColorAlphaType.PREMUL),
                ),
            )
            Canvas(it, SurfaceProps()).drawImageRect(
                image,
                Rect.makeWH(targetWidth.toFloat(), targetHeight.toFloat()),
            )
            SkiaImage.makeFromBitmap(it).toComposeImageBitmap()
        }
    }
}.getOrNull()
