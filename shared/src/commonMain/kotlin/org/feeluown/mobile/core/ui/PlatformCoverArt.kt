package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

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
internal expect fun rememberPlatformCoverImage(
    imageUrl: String?,
    maxSizePx: Int = DEFAULT_COVER_IMAGE_TARGET_SIZE_PX,
): ImageBitmap?

internal const val DEFAULT_COVER_IMAGE_TARGET_SIZE_PX = 256
private const val MAX_COVER_IMAGE_TARGET_SIZE_PX = 768
private const val PLATFORM_COVER_IMAGE_CACHE_MAX_BYTES = 32L * 1024L * 1024L
private const val PLATFORM_COVER_IMAGE_LOAD_PERMITS = 4

internal fun normalizedCoverImageTargetSizePx(sizePx: Int): Int =
    sizePx.coerceIn(1, MAX_COVER_IMAGE_TARGET_SIZE_PX)

internal fun coverTargetSizePx(maxWidth: Dp, maxHeight: Dp, density: Density): Int {
    val finiteWidth = maxWidth.value.takeIf { it.isFinite() && it > 0f }
    val finiteHeight = maxHeight.value.takeIf { it.isFinite() && it > 0f }
    val targetDp = maxOf(finiteWidth ?: 0f, finiteHeight ?: 0f)
    val targetPx = if (targetDp > 0f) {
        (targetDp * density.density).roundToInt()
    } else {
        DEFAULT_COVER_IMAGE_TARGET_SIZE_PX
    }
    return normalizedCoverImageTargetSizePx(targetPx)
}

internal fun coverImageCacheKey(imageUrl: String, maxSizePx: Int): String =
    "$imageUrl|${normalizedCoverImageTargetSizePx(maxSizePx)}"

private val qqCoverSizePattern = Regex("(?i)R\\d+x\\d+")
private val neteaseCoverSizePattern = Regex("(?i)([?&])param=\\d+y\\d+")
private val youtubeCoverVariantPattern = Regex(
    "(?i)(/vi/[^/]+/)(?:maxresdefault|hqdefault|mqdefault|sddefault|default)\\.jpg",
)
private val googleThumbnailSizePattern = Regex("(?i)=w\\d+-h\\d+")

internal fun preferredCoverImageUrl(imageUrl: String, maxSizePx: Int): String {
    val sizePx = normalizedCoverImageTargetSizePx(maxSizePx).coerceAtLeast(64)
    return when {
        imageUrl.contains("y.qq.com/", ignoreCase = true) ||
            imageUrl.contains("y.gtimg.cn/", ignoreCase = true) -> {
            qqCoverSizePattern.replace(imageUrl) { "R${sizePx}x${sizePx}" }
        }
        imageUrl.contains("music.126.net/", ignoreCase = true) -> {
            neteaseCoverSizePattern.replace(imageUrl) { match ->
                "${match.groupValues[1]}param=${sizePx}y${sizePx}"
            }
        }
        imageUrl.contains("i.ytimg.com/vi/", ignoreCase = true) -> {
            youtubeCoverVariantPattern.replace(imageUrl) { match ->
                "${match.groupValues[1]}${youtubeCoverVariant(sizePx)}.jpg"
            }
        }
        imageUrl.contains("googleusercontent.com/", ignoreCase = true) -> {
            googleThumbnailSizePattern.replace(imageUrl, "=w${sizePx}-h${sizePx}")
        }
        else -> imageUrl
    }
}

internal fun coverImageRequestUrls(imageUrl: String, maxSizePx: Int): List<String> {
    val preferredUrl = preferredCoverImageUrl(imageUrl, maxSizePx)
    return if (preferredUrl == imageUrl) listOf(imageUrl) else listOf(preferredUrl, imageUrl)
}

private fun youtubeCoverVariant(sizePx: Int): String = when {
    sizePx <= 160 -> "default"
    sizePx <= 360 -> "mqdefault"
    else -> "hqdefault"
}

internal object PlatformCoverImageCache {
    private val mutex = Mutex()
    private val loadPermits = Semaphore(PLATFORM_COVER_IMAGE_LOAD_PERMITS)
    private val images = mutableMapOf<String, ImageBitmap>()
    private val imageOrder = mutableListOf<String>()
    private val inFlight = mutableMapOf<String, CompletableDeferred<ImageBitmap?>>()
    private var imageBytes = 0L

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
        if (cached != null) {
            mutex.withLock {
                imageOrder.remove(key)
                imageOrder += key
            }
            return cached
        }

        val deferred = requireNotNull(pending)
        if (ownsLoad) {
            try {
                val image = loadPermits.withPermit { loader() }
                mutex.withLock {
                    inFlight.remove(key)
                    if (image != null) {
                        images.remove(key)?.let { imageBytes -= imageWeight(it) }
                        imageOrder.remove(key)
                        val weight = imageWeight(image)
                        if (weight <= PLATFORM_COVER_IMAGE_CACHE_MAX_BYTES) {
                            while (imageOrder.isNotEmpty() && imageBytes + weight > PLATFORM_COVER_IMAGE_CACHE_MAX_BYTES) {
                                images.remove(imageOrder.removeAt(0))?.let { imageBytes -= imageWeight(it) }
                            }
                            images[key] = image
                            imageOrder += key
                            imageBytes += weight
                        }
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

    private fun imageWeight(image: ImageBitmap): Long =
        image.width.toLong().coerceAtLeast(0L) * image.height.toLong().coerceAtLeast(0L) * 4L
}
