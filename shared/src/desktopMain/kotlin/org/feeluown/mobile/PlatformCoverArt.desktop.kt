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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import java.net.URI
import java.net.URL

private const val MAX_COVER_BYTES = 8 * 1024 * 1024

@Composable
actual fun PlatformCoverArt(
    title: String,
    imageUrl: String?,
    modifier: Modifier,
    placeholder: CoverPlaceholder,
) {
    var image by remember(imageUrl) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(imageUrl) {
        image = imageUrl?.takeIf { it.isNotBlank() }?.let {
            runCatching { loadCover(it) }.getOrNull()
        }
    }

    val bitmap = image
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = title,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        CoverFallback(placeholder = placeholder, modifier = modifier)
    }
}

private suspend fun loadCover(imageUrl: String): ImageBitmap? = withContext(Dispatchers.IO) {
    val bytes = when {
        imageUrl.startsWith("file:") -> URI(imageUrl).path?.let { File(it).readBytesIfSmall() }
        imageUrl.startsWith("http://") || imageUrl.startsWith("https://") -> URL(imageUrl).openConnection().run {
            connectTimeout = 15_000
            readTimeout = 20_000
            getInputStream().use { it.readBytes().takeIf { bytes -> bytes.size <= MAX_COVER_BYTES } }
        }
        else -> File(imageUrl).readBytesIfSmall()
    } ?: return@withContext null

    Image.makeFromEncoded(bytes).toComposeImageBitmap()
}

private fun File.readBytesIfSmall(): ByteArray? {
    if (!isFile || length() <= 0L || length() > MAX_COVER_BYTES) return null
    return readBytes()
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
