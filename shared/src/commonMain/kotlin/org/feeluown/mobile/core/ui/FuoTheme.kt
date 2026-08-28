package org.feeluown.mobile

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.ktx.themeColorOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.pow

private const val COVER_THEME_MAX_COLORS = 128
private const val REQUIRED_CONTRAST_RATIO = 4.5
private const val PLAYBACK_AMBIENT_SURFACE_BLEND = 0.32f
private const val PLAYBACK_AMBIENT_ACCENT_BLEND = 0.62f

private val LocalThemePaletteStyle = staticCompositionLocalOf { ThemePaletteStyle.Expressive }
private val LocalThemeColorSpec = staticCompositionLocalOf { ThemeColorSpec.Expressive_2025 }
private val LocalBaseTargetColorScheme = staticCompositionLocalOf<ColorScheme?> { null }
private val LocalPlaybackCoverTargetColorScheme = staticCompositionLocalOf<ColorScheme?> { null }

internal enum class PlaybackColorEmphasis {
    Ambient,
    Immersive,
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FuoTheme(
    themeMode: ThemeMode,
    themeColorScheme: ThemeColorScheme,
    themePaletteStyle: ThemePaletteStyle = ThemePaletteStyle.Expressive,
    themeColorSpec: ThemeColorSpec = ThemeColorSpec.Expressive_2025,
    content: @Composable () -> Unit,
) {
    val darkTheme = resolvedDarkTheme(themeMode, isSystemInDarkTheme())
    val targetColorScheme = fuoColorScheme(
        themeColorScheme = themeColorScheme,
        darkTheme = darkTheme,
        paletteStyle = themePaletteStyle,
        colorSpec = themeColorSpec,
    )
    val animatedColorScheme = rememberAnimatedColorScheme(
        target = targetColorScheme,
        labelPrefix = "app theme",
    )
    CompositionLocalProvider(
        LocalThemePaletteStyle provides themePaletteStyle,
        LocalThemeColorSpec provides themeColorSpec,
        LocalBaseTargetColorScheme provides targetColorScheme,
    ) {
        FuoExpressiveTheme(colorScheme = animatedColorScheme) {
            platformWindowSurfaceEffect(animatedColorScheme.surface)
            content()
        }
    }
}

@Composable
internal fun ProvidePlaybackColorEnvironment(
    themeMode: ThemeMode,
    dynamicCoverColorEnabled: Boolean,
    coverImageUrl: String?,
    isLoading: Boolean,
    content: @Composable () -> Unit,
) {
    val darkTheme = resolvedDarkTheme(themeMode, isSystemInDarkTheme())
    val paletteStyle = LocalThemePaletteStyle.current
    val colorSpec = LocalThemeColorSpec.current
    val coverColorSeed = rememberCoverColorSeed(
        dynamicCoverColorEnabled = dynamicCoverColorEnabled,
        coverImageUrl = coverImageUrl,
        darkTheme = darkTheme,
        paletteStyle = paletteStyle,
        colorSpec = colorSpec,
        preserveWhileLoading = isLoading,
    )
    val hasCoverColor = dynamicCoverColorEnabled &&
        (isLoading || !coverImageUrl.isNullOrBlank()) &&
        coverColorSeed != null
    val coverColorScheme = remember(
        coverColorSeed,
        darkTheme,
        hasCoverColor,
        paletteStyle,
        colorSpec,
    ) {
        if (hasCoverColor && coverColorSeed != null) {
            generatedColorScheme(
                seedColor = coverColorSeed,
                darkTheme = darkTheme,
                paletteStyle = paletteStyle,
                colorSpec = colorSpec,
            )
        } else {
            null
        }
    }
    CompositionLocalProvider(
        LocalPlaybackCoverTargetColorScheme provides coverColorScheme,
        content = content,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PlaybackDynamicColorTheme(
    emphasis: PlaybackColorEmphasis,
    content: @Composable () -> Unit,
) {
    val baseTargetColorScheme = LocalBaseTargetColorScheme.current ?: MaterialTheme.colorScheme
    val coverTargetColorScheme = LocalPlaybackCoverTargetColorScheme.current
    val targetColorScheme = remember(baseTargetColorScheme, coverTargetColorScheme, emphasis) {
        when {
            coverTargetColorScheme == null -> baseTargetColorScheme
            emphasis == PlaybackColorEmphasis.Immersive -> coverTargetColorScheme
            else -> ambientPlaybackColorScheme(baseTargetColorScheme, coverTargetColorScheme)
        }
    }
    val animatedColorScheme = rememberAnimatedColorScheme(
        target = targetColorScheme,
        labelPrefix = "playback ${emphasis.name.lowercase()} theme",
    )
    FuoExpressiveTheme(
        colorScheme = animatedColorScheme,
        content = content,
    )
}

internal fun ambientPlaybackColorScheme(
    base: ColorScheme,
    cover: ColorScheme,
): ColorScheme {
    val primary = lerp(base.primary, cover.primary, PLAYBACK_AMBIENT_ACCENT_BLEND)
    val primaryContainer = lerp(
        base.primaryContainer,
        cover.primaryContainer,
        PLAYBACK_AMBIENT_ACCENT_BLEND,
    )
    val secondary = lerp(base.secondary, cover.secondary, PLAYBACK_AMBIENT_ACCENT_BLEND)
    val secondaryContainer = lerp(
        base.secondaryContainer,
        cover.secondaryContainer,
        PLAYBACK_AMBIENT_ACCENT_BLEND,
    )
    val surfaceVariant = lerp(
        base.surfaceVariant,
        cover.surfaceVariant,
        PLAYBACK_AMBIENT_SURFACE_BLEND,
    )
    val surfaceContainer = lerp(
        base.surfaceContainer,
        cover.surfaceContainer,
        PLAYBACK_AMBIENT_SURFACE_BLEND,
    )
    val surfaceContainerHigh = lerp(
        base.surfaceContainerHigh,
        cover.surfaceContainerHigh,
        PLAYBACK_AMBIENT_SURFACE_BLEND,
    )
    val surfaceContainerHighest = lerp(
        base.surfaceContainerHighest,
        cover.surfaceContainerHighest,
        PLAYBACK_AMBIENT_SURFACE_BLEND,
    )
    val surfaceContainerLow = lerp(
        base.surfaceContainerLow,
        cover.surfaceContainerLow,
        PLAYBACK_AMBIENT_SURFACE_BLEND,
    )
    val surfaceContainerLowest = lerp(
        base.surfaceContainerLowest,
        cover.surfaceContainerLowest,
        PLAYBACK_AMBIENT_SURFACE_BLEND,
    )

    return base.copy(
        primary = primary,
        onPrimary = ensureThemeContrast(
            lerp(base.onPrimary, cover.onPrimary, PLAYBACK_AMBIENT_ACCENT_BLEND),
            listOf(primary),
        ),
        primaryContainer = primaryContainer,
        onPrimaryContainer = ensureThemeContrast(
            lerp(
                base.onPrimaryContainer,
                cover.onPrimaryContainer,
                PLAYBACK_AMBIENT_ACCENT_BLEND,
            ),
            listOf(primaryContainer),
        ),
        inversePrimary = lerp(
            base.inversePrimary,
            cover.inversePrimary,
            PLAYBACK_AMBIENT_ACCENT_BLEND,
        ),
        secondary = secondary,
        onSecondary = ensureThemeContrast(
            lerp(base.onSecondary, cover.onSecondary, PLAYBACK_AMBIENT_ACCENT_BLEND),
            listOf(secondary),
        ),
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = ensureThemeContrast(
            lerp(
                base.onSecondaryContainer,
                cover.onSecondaryContainer,
                PLAYBACK_AMBIENT_ACCENT_BLEND,
            ),
            listOf(secondaryContainer),
        ),
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = ensureThemeContrast(
            lerp(
                base.onSurfaceVariant,
                cover.onSurfaceVariant,
                PLAYBACK_AMBIENT_SURFACE_BLEND,
            ),
            listOf(surfaceVariant),
        ),
        surfaceTint = lerp(
            base.surfaceTint,
            cover.surfaceTint,
            PLAYBACK_AMBIENT_ACCENT_BLEND,
        ),
        onSurface = ensureThemeContrast(
            base.onSurface,
            listOf(
                base.surface,
                base.surfaceBright,
                base.surfaceDim,
                surfaceContainer,
                surfaceContainerHigh,
                surfaceContainerHighest,
                surfaceContainerLow,
                surfaceContainerLowest,
            ),
        ),
        outline = lerp(base.outline, cover.outline, PLAYBACK_AMBIENT_SURFACE_BLEND),
        outlineVariant = lerp(
            base.outlineVariant,
            cover.outlineVariant,
            PLAYBACK_AMBIENT_SURFACE_BLEND,
        ),
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainerLowest = surfaceContainerLowest,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FuoExpressiveTheme(
    colorScheme: ColorScheme,
    content: @Composable () -> Unit,
) {
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        shapes = FuoShapes,
        typography = FuoTypography,
        content = content,
    )
}

@Composable
private fun rememberCoverColorSeed(
    dynamicCoverColorEnabled: Boolean,
    coverImageUrl: String?,
    darkTheme: Boolean,
    paletteStyle: ThemePaletteStyle,
    colorSpec: ThemeColorSpec,
    preserveWhileLoading: Boolean,
): Color? {
    val normalizedCoverUrl = coverImageUrl?.takeIf { it.isNotBlank() }
    val coverImage = rememberPlatformCoverImage(
        if (dynamicCoverColorEnabled) normalizedCoverUrl else null,
    )
    val coverColorSeed by produceState<Color?>(
        initialValue = null,
        dynamicCoverColorEnabled,
        normalizedCoverUrl,
        darkTheme,
        paletteStyle,
        colorSpec,
        preserveWhileLoading,
        coverImage,
    ) {
        if (!dynamicCoverColorEnabled) {
            value = null
            return@produceState
        }
        if (normalizedCoverUrl == null) {
            if (!preserveWhileLoading) value = null
            return@produceState
        }
        if (coverImage == null) {
            return@produceState
        }

        val cacheKey = "$normalizedCoverUrl|$darkTheme|${paletteStyle.name}|${colorSpec.name}"
        CoverThemeSeedCache.get(cacheKey)?.let {
            value = it
            return@produceState
        }

        val seedColor = withContext(Dispatchers.Default) {
            coverImage.themeColorOrNull(maxColors = COVER_THEME_MAX_COLORS)
        }
        val validSeedColor = seedColor?.takeIf {
            hasAccessibleContrast(
                generatedColorScheme(
                    seedColor = it,
                    darkTheme = darkTheme,
                    paletteStyle = paletteStyle,
                    colorSpec = colorSpec,
                ),
            )
        }

        if (validSeedColor != null) {
            CoverThemeSeedCache.put(cacheKey, validSeedColor)
            value = validSeedColor
        }
    }
    return coverColorSeed
}

private val FuoShapes = Shapes()
private val FuoTypography = Typography()

internal fun resolvedDarkTheme(themeMode: ThemeMode, systemDark: Boolean): Boolean {
    return when (themeMode) {
        ThemeMode.System -> systemDark
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

@Composable
private fun fuoColorScheme(
    themeColorScheme: ThemeColorScheme,
    darkTheme: Boolean,
    paletteStyle: ThemePaletteStyle,
    colorSpec: ThemeColorSpec,
): ColorScheme {
    val platformPrimary = if (themeColorScheme == ThemeColorScheme.Dynamic) {
        platformDynamicColorScheme(darkTheme)?.primary
    } else {
        null
    }
    return remember(
        themeColorScheme,
        darkTheme,
        paletteStyle,
        colorSpec,
        platformPrimary,
    ) {
        if (platformPrimary != null) {
            val generated = generatedColorScheme(
                seedColor = platformPrimary,
                darkTheme = darkTheme,
                paletteStyle = paletteStyle,
                colorSpec = colorSpec,
            )
            if (hasAccessibleContrast(generated)) {
                return@remember generated
            }
        }
        presetColorScheme(
            preset = if (themeColorScheme == ThemeColorScheme.Dynamic) {
                ThemeColorScheme.ExpressiveDefault
            } else {
                themeColorScheme
            },
            darkTheme = darkTheme,
            paletteStyle = paletteStyle,
            colorSpec = colorSpec,
        )
    }
}

@Composable
internal expect fun platformDynamicColorScheme(darkTheme: Boolean): ColorScheme?

@Composable
internal expect fun platformWindowSurfaceEffect(surfaceColor: Color)

@Composable
internal fun themePreviewColor(
    themeColorScheme: ThemeColorScheme,
    darkTheme: Boolean,
    paletteStyle: ThemePaletteStyle = ThemePaletteStyle.Expressive,
    colorSpec: ThemeColorSpec = ThemeColorSpec.Expressive_2025,
): Color {
    val seedColor = if (themeColorScheme == ThemeColorScheme.Dynamic) {
        platformDynamicColorScheme(darkTheme)?.primary
            ?: themeSeedColor(ThemeColorScheme.ExpressiveDefault)
    } else {
        themeSeedColor(themeColorScheme)
    }
    return generatedColorScheme(
        seedColor = seedColor,
        darkTheme = darkTheme,
        paletteStyle = paletteStyle,
        colorSpec = colorSpec,
    ).primary
}

internal fun presetColorScheme(
    preset: ThemeColorScheme,
    darkTheme: Boolean,
    paletteStyle: ThemePaletteStyle = ThemePaletteStyle.Expressive,
    colorSpec: ThemeColorSpec = ThemeColorSpec.Expressive_2025,
): ColorScheme {
    return generatedColorScheme(
        seedColor = themeSeedColor(preset),
        darkTheme = darkTheme,
        paletteStyle = paletteStyle,
        colorSpec = colorSpec,
    )
}

internal fun themeSeedColor(preset: ThemeColorScheme): Color {
    return when (preset) {
        ThemeColorScheme.Dynamic -> themeSeedColor(ThemeColorScheme.ExpressiveDefault)
        ThemeColorScheme.ExpressiveDefault -> Color(0xFF6750A4)
        ThemeColorScheme.FuoGreen -> Color(0xFF246B43)
        ThemeColorScheme.OceanBlue -> Color(0xFF0066B3)
        ThemeColorScheme.Violet -> Color(0xFF7650B4)
        ThemeColorScheme.Rose -> Color(0xFFB13F66)
        ThemeColorScheme.Amber -> Color(0xFF8A5A00)
    }
}

internal fun generatedColorScheme(
    seedColor: Color,
    darkTheme: Boolean,
    paletteStyle: ThemePaletteStyle,
    colorSpec: ThemeColorSpec,
): ColorScheme {
    return dynamicColorScheme(
        seedColor = seedColor,
        isDark = darkTheme,
        style = paletteStyle.toMaterialKolorPaletteStyle(),
        specVersion = colorSpec.toMaterialKolorSpecVersion(),
    )
}

internal fun expressiveColorScheme(seedColor: Color, darkTheme: Boolean): ColorScheme {
    return generatedColorScheme(
        seedColor = seedColor,
        darkTheme = darkTheme,
        paletteStyle = ThemePaletteStyle.Expressive,
        colorSpec = ThemeColorSpec.Expressive_2025,
    )
}

private fun ThemePaletteStyle.toMaterialKolorPaletteStyle(): PaletteStyle = when (this) {
    ThemePaletteStyle.TonalSpot -> PaletteStyle.TonalSpot
    ThemePaletteStyle.Neutral -> PaletteStyle.Neutral
    ThemePaletteStyle.Vibrant -> PaletteStyle.Vibrant
    ThemePaletteStyle.Expressive -> PaletteStyle.Expressive
    ThemePaletteStyle.Rainbow -> PaletteStyle.Rainbow
    ThemePaletteStyle.FruitSalad -> PaletteStyle.FruitSalad
    ThemePaletteStyle.Monochrome -> PaletteStyle.Monochrome
    ThemePaletteStyle.Fidelity -> PaletteStyle.Fidelity
    ThemePaletteStyle.Content -> PaletteStyle.Content
}

private fun ThemeColorSpec.toMaterialKolorSpecVersion(): ColorSpec.SpecVersion = when (this) {
    ThemeColorSpec.Material3_2021 -> ColorSpec.SpecVersion.SPEC_2021
    ThemeColorSpec.Expressive_2025 -> ColorSpec.SpecVersion.SPEC_2025
}

internal fun hasAccessibleContrast(colorScheme: ColorScheme): Boolean {
    return listOf(
        colorScheme.primary to colorScheme.onPrimary,
        colorScheme.primaryContainer to colorScheme.onPrimaryContainer,
        colorScheme.secondary to colorScheme.onSecondary,
        colorScheme.secondaryContainer to colorScheme.onSecondaryContainer,
        colorScheme.tertiary to colorScheme.onTertiary,
        colorScheme.tertiaryContainer to colorScheme.onTertiaryContainer,
        colorScheme.error to colorScheme.onError,
        colorScheme.errorContainer to colorScheme.onErrorContainer,
        colorScheme.surface to colorScheme.onSurface,
        colorScheme.surfaceVariant to colorScheme.onSurfaceVariant,
        colorScheme.surfaceContainer to colorScheme.onSurface,
        colorScheme.surfaceContainerHigh to colorScheme.onSurface,
        colorScheme.surfaceContainerHighest to colorScheme.onSurface,
        colorScheme.inverseSurface to colorScheme.inverseOnSurface,
    ).all { (background, foreground) ->
        colorContrastRatio(foreground, background) >= REQUIRED_CONTRAST_RATIO
    }
}

internal fun colorContrastRatio(foreground: Color, background: Color): Double {
    val foregroundLuminance = relativeLuminance(foreground)
    val backgroundLuminance = relativeLuminance(background)
    val lighter = maxOf(foregroundLuminance, backgroundLuminance)
    val darker = minOf(foregroundLuminance, backgroundLuminance)
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(color: Color): Double {
    fun linearize(component: Float): Double {
        val value = component.toDouble()
        return if (value <= 0.03928) {
            value / 12.92
        } else {
            ((value + 0.055) / 1.055).pow(2.4)
        }
    }

    return linearize(color.red) * 0.2126 +
        linearize(color.green) * 0.7152 +
        linearize(color.blue) * 0.0722
}

private object CoverThemeSeedCache {
    private const val MAX_ENTRIES = 8
    private val mutex = Mutex()
    private val values = mutableMapOf<String, Color>()
    private val order = mutableListOf<String>()

    suspend fun get(key: String): Color? = mutex.withLock { values[key] }

    suspend fun put(key: String, value: Color) {
        mutex.withLock {
            values.remove(key)
            order.remove(key)
            if (order.size >= MAX_ENTRIES) {
                values.remove(order.removeAt(0))
            }
            values[key] = value
            order += key
        }
    }
}
