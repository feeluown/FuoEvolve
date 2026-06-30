package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun PlatformCoverArt(
    title: String,
    imageUrl: String?,
    modifier: Modifier,
)
