package org.feeluown.mobile

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.ui.LocalNavAnimatedContentScope

internal enum class ResourceCoverHeroType {
    Playlist,
    Artist,
    Album,
}

internal data class ResourceCoverHeroKey(
    val type: ResourceCoverHeroType,
    val providerId: String,
    val resourceId: String,
)

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
