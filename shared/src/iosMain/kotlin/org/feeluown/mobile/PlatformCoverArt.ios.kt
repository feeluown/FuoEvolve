package org.feeluown.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.posix.memcpy

@Composable
actual fun PlatformCoverArt(
    title: String,
    imageUrl: String?,
    modifier: Modifier,
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
        CoverFallback(title, modifier)
    }
}

@Composable
private fun CoverFallback(title: String, modifier: Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = title.firstOrNull()?.uppercase() ?: "F",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

private suspend fun loadCover(imageUrl: String): ImageBitmap? = withContext(Dispatchers.Default) {
    CoverArtMemoryCache[imageUrl]?.let { return@withContext it }
    val url = imageUrl.toNsUrl() ?: return@withContext null
    val data = NSData.dataWithContentsOfURL(url) ?: return@withContext null
    val bytes = data.toByteArray()
    if (bytes.isEmpty()) return@withContext null
    val bitmap = SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    CoverArtMemoryCache[imageUrl] = bitmap
    bitmap
}

private object CoverArtMemoryCache {
    private const val MAX_ENTRIES = 128
    private val values = linkedMapOf<String, ImageBitmap>()

    operator fun get(key: String): ImageBitmap? = synchronized(values) {
        values.remove(key)?.also { values[key] = it }
    }

    operator fun set(key: String, value: ImageBitmap) = synchronized(values) {
        values[key] = value
        while (values.size > MAX_ENTRIES) {
            values.remove(values.keys.first())
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray {
    val length = length.toInt()
    if (length <= 0) return ByteArray(0)
    val source = bytes ?: return ByteArray(0)
    return ByteArray(length).also { target ->
        target.usePinned {
            memcpy(it.addressOf(0), source, length.toULong())
        }
    }
}
