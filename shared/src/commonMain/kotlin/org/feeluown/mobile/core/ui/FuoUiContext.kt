package org.feeluown.mobile

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Cross-feature UI context belongs to the core UI layer. The app shell provides these values,
 * while feature/core UI may consume them without depending back on app-shell declarations.
 */
val LocalShareHandler = staticCompositionLocalOf<(SharePayload) -> Unit> { {} }
val LocalAppLayoutInfo = staticCompositionLocalOf { AppLayoutInfo() }

enum class AppWidthSizeClass {
    Compact,
    Medium,
    Expanded,
    Large,
    ExtraLarge,
}

enum class AppHeightSizeClass {
    Compact,
    Medium,
    Expanded,
}

data class AppLayoutInfo(
    val widthSizeClass: AppWidthSizeClass = AppWidthSizeClass.Compact,
    val heightSizeClass: AppHeightSizeClass = AppHeightSizeClass.Medium,
    /** Physical window orientation. Do not use this as a proxy for available layout width. */
    val isLandscape: Boolean = false,
    /** Enables existing two-column / denser feature layouts when the window has enough usable space. */
    val useWideLayout: Boolean = false,
    /** Hosts persistent wide-window navigation at the app-shell level. */
    val usePersistentNavigation: Boolean = false,
    /** Full player may use its side-by-side lyrics / artwork composition. */
    val useFullPlayerTwoPane: Boolean = false,
    val gridColumns: Int = 3,
)
