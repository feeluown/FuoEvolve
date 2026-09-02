package org.feeluown.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
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
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

@Composable
actual fun PlatformCoverArt(
    title: String,
    imageUrl: String?,
    modifier: Modifier,
    placeholder: CoverPlaceholder,
) {
    val bitmap = rememberPlatformCoverImage(imageUrl)
    if (bitmap != null) {
        Image(bitmap, title, modifier, contentScale = ContentScale.Crop)
    } else {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.primaryContainer) {
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

@Composable
internal actual fun rememberPlatformCoverImage(imageUrl: String?): ImageBitmap? {
    var image by remember(imageUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(imageUrl) {
        image = imageUrl?.takeIf { it.isNotBlank() }?.let { url ->
            runCatching { PlatformCoverImageCache.getOrLoad(url) { loadDesktopCover(url) } }.getOrNull()
        }
    }
    return image
}

private suspend fun loadDesktopCover(imageUrl: String): ImageBitmap? = withContext(Dispatchers.IO) {
    val resolvedUrl = when {
        imageUrl.startsWith("fuo-cover:") -> imageUrl.substringAfter('?', "")
            .split('&')
            .firstNotNullOfOrNull { entry ->
                val parts = entry.split('=', limit = 2)
                parts.getOrNull(1)?.takeIf { parts.firstOrNull() == "albumArt" && it.isNotBlank() }
            }
        else -> imageUrl
    } ?: return@withContext null

    val bytes = when {
        resolvedUrl.startsWith("file:") -> Files.readAllBytes(Paths.get(URI(resolvedUrl)))
        else -> DesktopResourceCache.cachedRemoteImage(resolvedUrl)
            ?.let(Files::readAllBytes)
            ?: URL(resolvedUrl).openStream().use { it.readBytes() }
    }
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}
