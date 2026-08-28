package org.feeluown.mobile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

@Composable
internal fun rememberAnimatedColorScheme(
    target: ColorScheme,
    labelPrefix: String,
): ColorScheme {
    val animationSpec = remember(FuoMotion.themeColorTransitionMillis) {
        tween<Color>(durationMillis = FuoMotion.themeColorTransitionMillis)
    }

    return target.copy(
        primary = animatedThemeColor(target.primary, animationSpec, "$labelPrefix primary"),
        onPrimary = animatedThemeColor(target.onPrimary, animationSpec, "$labelPrefix onPrimary"),
        primaryContainer = animatedThemeColor(target.primaryContainer, animationSpec, "$labelPrefix primaryContainer"),
        onPrimaryContainer = animatedThemeColor(target.onPrimaryContainer, animationSpec, "$labelPrefix onPrimaryContainer"),
        inversePrimary = animatedThemeColor(target.inversePrimary, animationSpec, "$labelPrefix inversePrimary"),
        secondary = animatedThemeColor(target.secondary, animationSpec, "$labelPrefix secondary"),
        onSecondary = animatedThemeColor(target.onSecondary, animationSpec, "$labelPrefix onSecondary"),
        secondaryContainer = animatedThemeColor(target.secondaryContainer, animationSpec, "$labelPrefix secondaryContainer"),
        onSecondaryContainer = animatedThemeColor(target.onSecondaryContainer, animationSpec, "$labelPrefix onSecondaryContainer"),
        tertiary = animatedThemeColor(target.tertiary, animationSpec, "$labelPrefix tertiary"),
        onTertiary = animatedThemeColor(target.onTertiary, animationSpec, "$labelPrefix onTertiary"),
        tertiaryContainer = animatedThemeColor(target.tertiaryContainer, animationSpec, "$labelPrefix tertiaryContainer"),
        onTertiaryContainer = animatedThemeColor(target.onTertiaryContainer, animationSpec, "$labelPrefix onTertiaryContainer"),
        background = animatedThemeColor(target.background, animationSpec, "$labelPrefix background"),
        onBackground = animatedThemeColor(target.onBackground, animationSpec, "$labelPrefix onBackground"),
        surface = animatedThemeColor(target.surface, animationSpec, "$labelPrefix surface"),
        onSurface = animatedThemeColor(target.onSurface, animationSpec, "$labelPrefix onSurface"),
        surfaceVariant = animatedThemeColor(target.surfaceVariant, animationSpec, "$labelPrefix surfaceVariant"),
        onSurfaceVariant = animatedThemeColor(target.onSurfaceVariant, animationSpec, "$labelPrefix onSurfaceVariant"),
        surfaceTint = animatedThemeColor(target.surfaceTint, animationSpec, "$labelPrefix surfaceTint"),
        inverseSurface = animatedThemeColor(target.inverseSurface, animationSpec, "$labelPrefix inverseSurface"),
        inverseOnSurface = animatedThemeColor(target.inverseOnSurface, animationSpec, "$labelPrefix inverseOnSurface"),
        error = animatedThemeColor(target.error, animationSpec, "$labelPrefix error"),
        onError = animatedThemeColor(target.onError, animationSpec, "$labelPrefix onError"),
        errorContainer = animatedThemeColor(target.errorContainer, animationSpec, "$labelPrefix errorContainer"),
        onErrorContainer = animatedThemeColor(target.onErrorContainer, animationSpec, "$labelPrefix onErrorContainer"),
        outline = animatedThemeColor(target.outline, animationSpec, "$labelPrefix outline"),
        outlineVariant = animatedThemeColor(target.outlineVariant, animationSpec, "$labelPrefix outlineVariant"),
        scrim = animatedThemeColor(target.scrim, animationSpec, "$labelPrefix scrim"),
        surfaceBright = animatedThemeColor(target.surfaceBright, animationSpec, "$labelPrefix surfaceBright"),
        surfaceDim = animatedThemeColor(target.surfaceDim, animationSpec, "$labelPrefix surfaceDim"),
        surfaceContainer = animatedThemeColor(target.surfaceContainer, animationSpec, "$labelPrefix surfaceContainer"),
        surfaceContainerHigh = animatedThemeColor(target.surfaceContainerHigh, animationSpec, "$labelPrefix surfaceContainerHigh"),
        surfaceContainerHighest = animatedThemeColor(target.surfaceContainerHighest, animationSpec, "$labelPrefix surfaceContainerHighest"),
        surfaceContainerLow = animatedThemeColor(target.surfaceContainerLow, animationSpec, "$labelPrefix surfaceContainerLow"),
        surfaceContainerLowest = animatedThemeColor(target.surfaceContainerLowest, animationSpec, "$labelPrefix surfaceContainerLowest"),
        primaryFixed = animatedThemeColor(target.primaryFixed, animationSpec, "$labelPrefix primaryFixed"),
        primaryFixedDim = animatedThemeColor(target.primaryFixedDim, animationSpec, "$labelPrefix primaryFixedDim"),
        onPrimaryFixed = animatedThemeColor(target.onPrimaryFixed, animationSpec, "$labelPrefix onPrimaryFixed"),
        onPrimaryFixedVariant = animatedThemeColor(target.onPrimaryFixedVariant, animationSpec, "$labelPrefix onPrimaryFixedVariant"),
        secondaryFixed = animatedThemeColor(target.secondaryFixed, animationSpec, "$labelPrefix secondaryFixed"),
        secondaryFixedDim = animatedThemeColor(target.secondaryFixedDim, animationSpec, "$labelPrefix secondaryFixedDim"),
        onSecondaryFixed = animatedThemeColor(target.onSecondaryFixed, animationSpec, "$labelPrefix onSecondaryFixed"),
        onSecondaryFixedVariant = animatedThemeColor(target.onSecondaryFixedVariant, animationSpec, "$labelPrefix onSecondaryFixedVariant"),
        tertiaryFixed = animatedThemeColor(target.tertiaryFixed, animationSpec, "$labelPrefix tertiaryFixed"),
        tertiaryFixedDim = animatedThemeColor(target.tertiaryFixedDim, animationSpec, "$labelPrefix tertiaryFixedDim"),
        onTertiaryFixed = animatedThemeColor(target.onTertiaryFixed, animationSpec, "$labelPrefix onTertiaryFixed"),
        onTertiaryFixedVariant = animatedThemeColor(target.onTertiaryFixedVariant, animationSpec, "$labelPrefix onTertiaryFixedVariant"),
    )
}

@Composable
private fun animatedThemeColor(
    target: Color,
    animationSpec: FiniteAnimationSpec<Color>,
    label: String,
): Color {
    val animated by animateColorAsState(
        targetValue = target,
        animationSpec = animationSpec,
        label = label,
    )
    return animated
}
