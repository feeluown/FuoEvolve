package org.feeluown.mobile

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

@Composable
actual fun PlatformCoverArt(
    title: String,
    imageUrl: String?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    var image by remember(imageUrl) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(imageUrl) {
        image = imageUrl?.takeIf { it.isNotBlank() }?.let {
            runCatching { loadCover(context, it) }.getOrNull()
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

private suspend fun loadCover(context: Context, imageUrl: String): ImageBitmap? = withContext(Dispatchers.IO) {
    val uri = Uri.parse(imageUrl)
    when (uri.scheme) {
        "fuo-cover" -> {
            val albumArt = uri.getQueryParameter("albumArt").orEmpty()
            val audio = uri.getQueryParameter("audio").orEmpty()
            albumArt.takeIf { it.isNotBlank() }?.let { loadDirectCover(context, it) }
                ?: audio.takeIf { it.isNotBlank() }?.let { loadEmbeddedCover(context, it) }
        }
        "content", "file" -> loadDirectCover(context, imageUrl) ?: loadEmbeddedCover(context, imageUrl)
        "http", "https" -> loadDirectCover(context, imageUrl)
        else -> null
    }
}

private fun loadDirectCover(context: Context, imageUrl: String): ImageBitmap? {
    val uri = Uri.parse(imageUrl)
    val bitmap = when (uri.scheme) {
        "content", "file" -> context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
        "http", "https" -> loadCachedRemoteCover(context, imageUrl)
        else -> null
    }
    return bitmap?.asImageBitmap()
}

private fun loadCachedRemoteCover(context: Context, imageUrl: String) =
    AndroidResourceCache.cachedImage(context, imageUrl)?.let { BitmapFactory.decodeFile(it.path) }
        ?: URL(imageUrl).openStream().use(BitmapFactory::decodeStream)

private fun loadEmbeddedCover(context: Context, imageUrl: String): ImageBitmap? {
    val uri = Uri.parse(imageUrl)
    val retriever = MediaMetadataRetriever()
    return try {
        when (uri.scheme) {
            "content" -> retriever.setDataSource(context, uri)
            "file" -> retriever.setDataSource(requireNotNull(uri.path))
            else -> return null
        }
        val bytes = retriever.embeddedPicture ?: return null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    } catch (_: Throwable) {
        null
    } finally {
        runCatching { retriever.release() }
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
