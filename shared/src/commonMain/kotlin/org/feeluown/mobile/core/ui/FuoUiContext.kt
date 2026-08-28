package org.feeluown.mobile

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Cross-feature UI context belongs to the core UI layer. The app shell provides these values,
 * while feature/core UI may consume them without depending back on app-shell declarations.
 */
val LocalShareHandler = staticCompositionLocalOf<(SharePayload) -> Unit> { {} }
val LocalAppLayoutInfo = staticCompositionLocalOf { AppLayoutInfo() }

data class AppLayoutInfo(
    val isLandscape: Boolean = false,
    val useWideLayout: Boolean = false,
    val gridColumns: Int = 3,
)
