package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

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
