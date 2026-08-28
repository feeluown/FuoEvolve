package org.feeluown.mobile

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

private const val MIN_THEME_TRANSITION_CONTRAST_RATIO = 4.5
private const val CONTRAST_SEARCH_ITERATIONS = 14

@Composable
internal fun rememberAnimatedColorScheme(
    target: ColorScheme,
    labelPrefix: String,
): ColorScheme {
    val animationSpec = remember(FuoMotion.themeColorTransitionMillis) {
        tween<Color>(durationMillis = FuoMotion.themeColorTransitionMillis)
    }

    val primary = animatedThemeColor(target.primary, animationSpec, "$labelPrefix primary")
    val primaryContainer = animatedThemeColor(
        target.primaryContainer,
        animationSpec,
        "$labelPrefix primaryContainer",
    )
    val secondary = animatedThemeColor(target.secondary, animationSpec, "$labelPrefix secondary")
    val secondaryContainer = animatedThemeColor(
        target.secondaryContainer,
        animationSpec,
        "$labelPrefix secondaryContainer",
    )
    val tertiary = animatedThemeColor(target.tertiary, animationSpec, "$labelPrefix tertiary")
    val tertiaryContainer = animatedThemeColor(
        target.tertiaryContainer,
        animationSpec,
        "$labelPrefix tertiaryContainer",
    )
    val background = animatedThemeColor(target.background, animationSpec, "$labelPrefix background")
    val surface = animatedThemeColor(target.surface, animationSpec, "$labelPrefix surface")
    val surfaceVariant = animatedThemeColor(
        target.surfaceVariant,
        animationSpec,
        "$labelPrefix surfaceVariant",
    )
    val inverseSurface = animatedThemeColor(
        target.inverseSurface,
        animationSpec,
        "$labelPrefix inverseSurface",
    )
    val error = animatedThemeColor(target.error, animationSpec, "$labelPrefix error")
    val errorContainer = animatedThemeColor(
        target.errorContainer,
        animationSpec,
        "$labelPrefix errorContainer",
    )
    val surfaceBright = animatedThemeColor(
        target.surfaceBright,
        animationSpec,
        "$labelPrefix surfaceBright",
    )
    val surfaceDim = animatedThemeColor(target.surfaceDim, animationSpec, "$labelPrefix surfaceDim")
    val surfaceContainer = animatedThemeColor(
        target.surfaceContainer,
        animationSpec,
        "$labelPrefix surfaceContainer",
    )
    val surfaceContainerHigh = animatedThemeColor(
        target.surfaceContainerHigh,
        animationSpec,
        "$labelPrefix surfaceContainerHigh",
    )
    val surfaceContainerHighest = animatedThemeColor(
        target.surfaceContainerHighest,
        animationSpec,
        "$labelPrefix surfaceContainerHighest",
    )
    val surfaceContainerLow = animatedThemeColor(
        target.surfaceContainerLow,
        animationSpec,
        "$labelPrefix surfaceContainerLow",
    )
    val surfaceContainerLowest = animatedThemeColor(
        target.surfaceContainerLowest,
        animationSpec,
        "$labelPrefix surfaceContainerLowest",
    )
    val primaryFixed = animatedThemeColor(
        target.primaryFixed,
        animationSpec,
        "$labelPrefix primaryFixed",
    )
    val primaryFixedDim = animatedThemeColor(
        target.primaryFixedDim,
        animationSpec,
        "$labelPrefix primaryFixedDim",
    )
    val secondaryFixed = animatedThemeColor(
        target.secondaryFixed,
        animationSpec,
        "$labelPrefix secondaryFixed",
    )
    val secondaryFixedDim = animatedThemeColor(
        target.secondaryFixedDim,
        animationSpec,
        "$labelPrefix secondaryFixedDim",
    )
    val tertiaryFixed = animatedThemeColor(
        target.tertiaryFixed,
        animationSpec,
        "$labelPrefix tertiaryFixed",
    )
    val tertiaryFixedDim = animatedThemeColor(
        target.tertiaryFixedDim,
        animationSpec,
        "$labelPrefix tertiaryFixedDim",
    )

    return target.copy(
        primary = primary,
        onPrimary = animatedContrastThemeColor(
            target.onPrimary,
            animationSpec,
            "$labelPrefix onPrimary",
            primary,
        ),
        primaryContainer = primaryContainer,
        onPrimaryContainer = animatedContrastThemeColor(
            target.onPrimaryContainer,
            animationSpec,
            "$labelPrefix onPrimaryContainer",
            primaryContainer,
        ),
        inversePrimary = animatedThemeColor(target.inversePrimary, animationSpec, "$labelPrefix inversePrimary"),
        secondary = secondary,
        onSecondary = animatedContrastThemeColor(
            target.onSecondary,
            animationSpec,
            "$labelPrefix onSecondary",
            secondary,
        ),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = animatedContrastThemeColor(
            target.onSecondaryContainer,
            animationSpec,
            "$labelPrefix onSecondaryContainer",
            secondaryContainer,
        ),
        tertiary = tertiary,
        onTertiary = animatedContrastThemeColor(
            target.onTertiary,
            animationSpec,
            "$labelPrefix onTertiary",
            tertiary,
        ),
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = animatedContrastThemeColor(
            target.onTertiaryContainer,
            animationSpec,
            "$labelPrefix onTertiaryContainer",
            tertiaryContainer,
        ),
        background = background,
        onBackground = animatedContrastThemeColor(
            target.onBackground,
            animationSpec,
            "$labelPrefix onBackground",
            background,
        ),
        surface = surface,
        onSurface = animatedContrastThemeColor(
            target.onSurface,
            animationSpec,
            "$labelPrefix onSurface",
            surface,
            surfaceBright,
            surfaceDim,
            surfaceContainer,
            surfaceContainerHigh,
            surfaceContainerHighest,
            surfaceContainerLow,
            surfaceContainerLowest,
        ),
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = animatedContrastThemeColor(
            target.onSurfaceVariant,
            animationSpec,
            "$labelPrefix onSurfaceVariant",
            surfaceVariant,
        ),
        surfaceTint = animatedThemeColor(target.surfaceTint, animationSpec, "$labelPrefix surfaceTint"),
        inverseSurface = inverseSurface,
        inverseOnSurface = animatedContrastThemeColor(
            target.inverseOnSurface,
            animationSpec,
            "$labelPrefix inverseOnSurface",
            inverseSurface,
        ),
        error = error,
        onError = animatedContrastThemeColor(
            target.onError,
            animationSpec,
            "$labelPrefix onError",
            error,
        ),
        errorContainer = errorContainer,
        onErrorContainer = animatedContrastThemeColor(
            target.onErrorContainer,
            animationSpec,
            "$labelPrefix onErrorContainer",
            errorContainer,
        ),
        outline = animatedThemeColor(target.outline, animationSpec, "$labelPrefix outline"),
        outlineVariant = animatedThemeColor(target.outlineVariant, animationSpec, "$labelPrefix outlineVariant"),
        scrim = animatedThemeColor(target.scrim, animationSpec, "$labelPrefix scrim"),
        surfaceBright = surfaceBright,
        surfaceDim = surfaceDim,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
        primaryFixed = primaryFixed,
        primaryFixedDim = primaryFixedDim,
        onPrimaryFixed = animatedContrastThemeColor(
            target.onPrimaryFixed,
            animationSpec,
            "$labelPrefix onPrimaryFixed",
            primaryFixed,
            primaryFixedDim,
        ),
        onPrimaryFixedVariant = animatedThemeColor(
            target.onPrimaryFixedVariant,
            animationSpec,
            "$labelPrefix onPrimaryFixedVariant",
        ),
        secondaryFixed = secondaryFixed,
        secondaryFixedDim = secondaryFixedDim,
        onSecondaryFixed = animatedContrastThemeColor(
            target.onSecondaryFixed,
            animationSpec,
            "$labelPrefix onSecondaryFixed",
            secondaryFixed,
            secondaryFixedDim,
        ),
        onSecondaryFixedVariant = animatedThemeColor(
            target.onSecondaryFixedVariant,
            animationSpec,
            "$labelPrefix onSecondaryFixedVariant",
        ),
        tertiaryFixed = tertiaryFixed,
        tertiaryFixedDim = tertiaryFixedDim,
        onTertiaryFixed = animatedContrastThemeColor(
            target.onTertiaryFixed,
            animationSpec,
            "$labelPrefix onTertiaryFixed",
            tertiaryFixed,
            tertiaryFixedDim,
        ),
        onTertiaryFixedVariant = animatedThemeColor(
            target.onTertiaryFixedVariant,
            animationSpec,
            "$labelPrefix onTertiaryFixedVariant",
        ),
    )
}

@Composable
private fun animatedContrastThemeColor(
    target: Color,
    animationSpec: FiniteAnimationSpec<Color>,
    label: String,
    vararg backgrounds: Color,
): Color {
    val animated = animatedThemeColor(target, animationSpec, label)
    return ensureThemeContrast(animated, backgrounds.asList())
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

internal fun ensureThemeContrast(
    foreground: Color,
    backgrounds: List<Color>,
    minimumRatio: Double = MIN_THEME_TRANSITION_CONTRAST_RATIO,
): Color {
    if (backgrounds.isEmpty() || backgrounds.all { colorContrastRatio(foreground, it) >= minimumRatio }) {
        return foreground
    }

    fun minimumContrast(candidate: Color): Double = backgrounds.minOf {
        colorContrastRatio(candidate, it)
    }

    val blackContrast = minimumContrast(Color.Black)
    val whiteContrast = minimumContrast(Color.White)
    val anchor = if (blackContrast >= whiteContrast) Color.Black else Color.White
    val anchorContrast = maxOf(blackContrast, whiteContrast)
    if (anchorContrast < minimumRatio) {
        return anchor
    }

    var low = 0f
    var high = 1f
    repeat(CONTRAST_SEARCH_ITERATIONS) {
        val fraction = (low + high) / 2f
        if (minimumContrast(lerp(foreground, anchor, fraction)) >= minimumRatio) {
            high = fraction
        } else {
            low = fraction
        }
    }
    return lerp(foreground, anchor, high)
}
