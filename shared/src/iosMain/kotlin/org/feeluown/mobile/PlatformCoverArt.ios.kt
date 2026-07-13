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
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.NSURLSession
import platform.Foundation.dataTaskWithURL
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Composable
actual fun PlatformCoverArt(title: String, imageUrl: String?, modifier: Modifier) {
    var image by remember(imageUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(imageUrl) {
        image = imageUrl?.takeIf { it.isNotBlank() }?.let { loadImage(it) }
    }
    val bitmap = image
    if (bitmap != null) {
        Image(bitmap, title, modifier, contentScale = ContentScale.Crop)
    } else {
        Surface(modifier = modifier, color = MaterialTheme.colorScheme.primaryContainer) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    title.firstOrNull()?.uppercase() ?: "F",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private suspend fun loadImage(imageUrl: String): ImageBitmap? = withContext(Dispatchers.Default) {
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
    val url = NSURL.URLWithString(resolvedUrl) ?: return@withContext null
    val data = fetchData(url) ?: return@withContext null
    val bytes = data.bytes?.reinterpret<ByteVar>()?.readBytes(data.length.toInt()) ?: return@withContext null
    runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}

private suspend fun fetchData(url: NSURL): NSData? = suspendCoroutine { continuation ->
    NSURLSession.sharedSession.dataTaskWithURL(url) { data, _, _ ->
        continuation.resume(data)
    }.resume()
}
