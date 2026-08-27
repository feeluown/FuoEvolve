package org.feeluown.mobile

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import kotlin.random.Random

internal enum class ResourceCoverHeroType {
    Playlist,
    Artist,
    Album,
}

internal data class ResourceCoverHeroKey(
    val type: ResourceCoverHeroType,
    val providerId: String,
    val resourceId: String,
    val sourceInstanceId: String? = null,
) {
    fun matchesResource(other: ResourceCoverHeroKey): Boolean =
        type == other.type && providerId == other.providerId && resourceId == other.resourceId
}

/**
 * Coordinates the transient source instance for resource-cover Hero transitions.
 *
 * Resource identity alone is insufficient because the same playlist/album/artist can be rendered
 * more than once on one screen. The clicked card activates its own saveable instance token; the
 * detail destination then uses that exact key for both the forward and pop transition without
 * leaking presentation-only identity into navigation/domain models.
 */
internal class ResourceHeroCoordinator {
    var activeKey by mutableStateOf<ResourceCoverHeroKey?>(null)
        private set

    fun activate(key: ResourceCoverHeroKey) {
        activeKey = key
    }

    fun destinationKey(identity: ResourceCoverHeroKey?): ResourceCoverHeroKey? {
        if (identity == null) return null
        return activeKey?.takeIf { it.matchesResource(identity) } ?: identity
    }
}

internal val LocalResourceHeroCoordinator = staticCompositionLocalOf<ResourceHeroCoordinator?> { null }

@Composable
internal fun rememberResourceHeroSourceKey(identity: ResourceCoverHeroKey): ResourceCoverHeroKey {
    val sourceInstanceId = rememberSaveable(identity.type.name, identity.providerId, identity.resourceId) {
        Random.nextLong().toString()
    }
    return remember(identity, sourceInstanceId) {
        identity.copy(sourceInstanceId = sourceInstanceId)
    }
}

internal fun ProviderPlaylist.coverHeroKey(): ResourceCoverHeroKey = ResourceCoverHeroKey(
    type = ResourceCoverHeroType.Playlist,
    providerId = providerId,
    resourceId = id,
)

internal fun ProviderMediaItem.coverHeroKey(): ResourceCoverHeroKey = ResourceCoverHeroKey(
    type = when (type) {
        ProviderMediaItemType.Artist -> ResourceCoverHeroType.Artist
        ProviderMediaItemType.Album -> ResourceCoverHeroType.Album
    },
    providerId = providerId,
    resourceId = id,
)

internal fun MusicTrack.detailCoverHeroKey(placeholder: CoverPlaceholder): ResourceCoverHeroKey? {
    if (sourceType != TrackSourceType.Provider || source.isBlank() || id.isBlank()) return null
    val type = when (placeholder) {
        CoverPlaceholder.Playlist -> ResourceCoverHeroType.Playlist
        CoverPlaceholder.Artist -> ResourceCoverHeroType.Artist
        CoverPlaceholder.Album -> ResourceCoverHeroType.Album
        CoverPlaceholder.Song,
        CoverPlaceholder.DailyRecommendation -> return null
    }
    return ResourceCoverHeroKey(
        type = type,
        providerId = source,
        resourceId = id,
    )
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun Modifier.fuoNavigationHero(key: Any?): Modifier {
    if (key == null) return this
    val sharedTransitionScope = LocalAppSharedTransitionScope.current ?: return this
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    return with(sharedTransitionScope) {
        this@fuoNavigationHero.sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = animatedVisibilityScope,
        )
    }
}
