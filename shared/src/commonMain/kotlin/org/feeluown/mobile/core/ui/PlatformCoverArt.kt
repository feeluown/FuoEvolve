package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class CoverPlaceholder {
    Song,
    Album,
    Artist,
    Playlist,
    DailyRecommendation,
}

@Composable
expect fun PlatformCoverArt(
    title: String,
    imageUrl: String?,
    modifier: Modifier,
    placeholder: CoverPlaceholder,
)

@Composable
internal expect fun rememberPlatformCoverImage(imageUrl: String?): ImageBitmap?

private const val PLATFORM_COVER_IMAGE_CACHE_ENTRIES = 4

internal object PlatformCoverImageCache {
    private val mutex = Mutex()
    private val images = mutableMapOf<String, ImageBitmap>()
    private val imageOrder = mutableListOf<String>()
    private val inFlight = mutableMapOf<String, CompletableDeferred<ImageBitmap?>>()

    suspend fun getOrLoad(key: String, loader: suspend () -> ImageBitmap?): ImageBitmap? {
        var cached: ImageBitmap? = null
        var pending: CompletableDeferred<ImageBitmap?>? = null
        var ownsLoad = false
        mutex.withLock {
            cached = images[key]
            if (cached == null) {
                pending = inFlight[key]
                if (pending == null) {
                    pending = CompletableDeferred()
                    inFlight[key] = requireNotNull(pending)
                    ownsLoad = true
                }
            }
        }
        if (cached != null) return cached

        val deferred = requireNotNull(pending)
        if (ownsLoad) {
            try {
                val image = loader()
                mutex.withLock {
                    inFlight.remove(key)
                    if (image != null) {
                        images.remove(key)
                        imageOrder.remove(key)
                        if (imageOrder.size >= PLATFORM_COVER_IMAGE_CACHE_ENTRIES) {
                            images.remove(imageOrder.removeAt(0))
                        }
                        images[key] = image
                        imageOrder += key
                    }
                }
                deferred.complete(image)
            } catch (throwable: Throwable) {
                mutex.withLock { inFlight.remove(key) }
                deferred.completeExceptionally(throwable)
                throw throwable
            }
        }
        return deferred.await()
    }
}
