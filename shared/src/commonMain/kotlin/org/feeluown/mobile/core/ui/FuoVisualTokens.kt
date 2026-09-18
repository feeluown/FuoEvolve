package org.feeluown.mobile

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Refined Expressive is a semantic layer over Material 3, not a second color system. */
internal object FuoVisualTokens {
    val brandSeed = Color(0xFF246B43)

    // Images remain visually quieter than action containers; pill shapes belong to controls.
    val artwork = RoundedCornerShape(12.dp)
    val content = RoundedCornerShape(16.dp)
    val group = RoundedCornerShape(20.dp)
    val floating = RoundedCornerShape(24.dp)
    val overlay = RoundedCornerShape(28.dp)

    val shapes = Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = content,
        large = group,
        extraLarge = overlay,
    )

    private val defaultType = Typography()
    val typography = Typography(
        displayLarge = defaultType.displayLarge.copy(fontWeight = FontWeight.Bold),
        displayMedium = defaultType.displayMedium.copy(fontWeight = FontWeight.Bold),
        displaySmall = defaultType.displaySmall.copy(fontWeight = FontWeight.SemiBold),
        headlineLarge = defaultType.headlineLarge.copy(fontWeight = FontWeight.SemiBold),
        headlineMedium = defaultType.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        headlineSmall = defaultType.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = defaultType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = defaultType.titleMedium.copy(fontWeight = FontWeight.Medium),
        titleSmall = defaultType.titleSmall.copy(fontWeight = FontWeight.Medium),
        bodyLarge = defaultType.bodyLarge,
        bodyMedium = defaultType.bodyMedium,
        bodySmall = defaultType.bodySmall,
        labelLarge = defaultType.labelLarge.copy(fontWeight = FontWeight.Medium),
        labelMedium = defaultType.labelMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = defaultType.labelSmall,
    )
}

/** Elevation is reserved for floating UI; ordinary content relies on tone and spacing. */
internal object FuoElevation {
    val content = 0.dp
    val floating = 2.dp
    val overlay = 3.dp
}

/** Surface roles preserve the user's color algorithm, dynamic wallpaper colors and dark mode. */
internal object FuoSurfaceColors {
    fun page(scheme: ColorScheme): Color = scheme.surface
    fun section(scheme: ColorScheme): Color = scheme.surfaceContainerLow
    fun interactive(scheme: ColorScheme): Color = scheme.surfaceContainer
    fun selected(scheme: ColorScheme): Color = scheme.secondaryContainer
    fun floating(scheme: ColorScheme): Color = scheme.surfaceContainerHigh
}

@Composable
internal fun fuoSectionColor(): Color = FuoSurfaceColors.section(MaterialTheme.colorScheme)

@Composable
internal fun fuoInteractiveColor(): Color = FuoSurfaceColors.interactive(MaterialTheme.colorScheme)
