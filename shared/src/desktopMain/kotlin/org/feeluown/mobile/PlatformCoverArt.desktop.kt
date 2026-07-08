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
        CoverFallback(title = title, modifier = modifier)
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
