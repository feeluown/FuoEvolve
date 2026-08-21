package org.feeluown.mobile

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val LocalShareHandler = staticCompositionLocalOf<(SharePayload) -> Unit> { {} }
val LocalAppLayoutInfo = staticCompositionLocalOf { AppLayoutInfo() }

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalPlayerSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

data class AppLayoutInfo(
    val isLandscape: Boolean = false,
    val useWideLayout: Boolean = false,
    val gridColumns: Int = 3,
)

internal fun appLayoutInfoFor(maxWidth: Dp, maxHeight: Dp): AppLayoutInfo {
    val isLandscape = maxWidth > maxHeight
    return AppLayoutInfo(
        isLandscape = isLandscape,
        useWideLayout = isLandscape && maxWidth >= 640.dp,
        gridColumns = when {
            maxWidth >= 980.dp -> 6
            maxWidth >= 760.dp -> 5
            maxWidth >= 640.dp -> 4
            else -> 3
        },
    )
}
