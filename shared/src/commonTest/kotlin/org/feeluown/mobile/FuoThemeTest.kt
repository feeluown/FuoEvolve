package org.feeluown.mobile

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FuoThemeTest {
    @Test
    fun fixedPresetsGenerateAccessibleLightAndDarkSchemes() {
        ThemeColorScheme.entries
            .filter { it != ThemeColorScheme.Dynamic }
            .forEach { preset ->
                assertTrue(
                    hasAccessibleContrast(presetColorScheme(preset, darkTheme = false)),
                    "light scheme for $preset",
                )
                assertTrue(
                    hasAccessibleContrast(presetColorScheme(preset, darkTheme = true)),
                    "dark scheme for $preset",
                )
            }
    }

    @Test
    fun coverSeedsGenerateAccessibleSchemesInBothModes() {
        listOf(
            Color(0xFFFF2020),
            Color(0xFF102040),
            Color(0xFF22AA77),
            Color(0xFF8060D0),
        ).forEach { seed ->
            assertTrue(hasAccessibleContrast(expressiveColorScheme(seed, darkTheme = false)))
            assertTrue(hasAccessibleContrast(expressiveColorScheme(seed, darkTheme = true)))
        }
    }

    @Test
    fun ambientPlaybackColorsKeepBaseSurfaceAndBlendPlaybackContainers() {
        val base = expressiveColorScheme(Color(0xFF6750A4), darkTheme = false)
        val cover = expressiveColorScheme(Color(0xFFCC3A45), darkTheme = false)
        val ambient = ambientPlaybackColorScheme(base, cover)

        assertEquals(base.surface, ambient.surface)
        assertNotEquals(base.surfaceContainerHigh, ambient.surfaceContainerHigh)
        assertNotEquals(cover.surfaceContainerHigh, ambient.surfaceContainerHigh)
        assertNotEquals(base.primary, ambient.primary)
        assertTrue(hasAccessibleContrast(ambient))
    }

    @Test
    fun ambientPlaybackColorsRemainAccessibleInLightAndDarkModes() {
        listOf(false, true).forEach { darkTheme ->
            val base = expressiveColorScheme(Color(0xFF6750A4), darkTheme)
            listOf(
                Color(0xFFFF2020),
                Color(0xFF102040),
                Color(0xFF22AA77),
                Color(0xFF8060D0),
            ).forEach { seed ->
                val cover = expressiveColorScheme(seed, darkTheme)
                assertTrue(
                    hasAccessibleContrast(ambientPlaybackColorScheme(base, cover)),
                    "ambient scheme for $seed dark=$darkTheme",
                )
            }
        }
    }

    @Test
    fun namedPresetsKeepTheirConfiguredSeedColors() {
        assertEquals(Color(0xFF6750A4), themeSeedColor(ThemeColorScheme.ExpressiveDefault))
        assertEquals(Color(0xFF246B43), themeSeedColor(ThemeColorScheme.FuoGreen))
        assertEquals(Color(0xFF0066B3), themeSeedColor(ThemeColorScheme.OceanBlue))
        assertEquals(Color(0xFF7650B4), themeSeedColor(ThemeColorScheme.Violet))
        assertEquals(Color(0xFFB13F66), themeSeedColor(ThemeColorScheme.Rose))
        assertEquals(Color(0xFF8A5A00), themeSeedColor(ThemeColorScheme.Amber))
    }

    @Test
    fun contrastRatioUsesForegroundAndBackgroundLuminance() {
        assertEquals(21.0, colorContrastRatio(Color.White, Color.Black), absoluteTolerance = 0.01)
        assertTrue(colorContrastRatio(Color.Black, Color.White) >= 21.0 - 0.01)
    }

    @Test
    fun transitionContrastCorrectionProtectsLowContrastMidpoint() {
        val midpoint = Color(0xFF777777)
        val corrected = ensureThemeContrast(
            foreground = midpoint,
            backgrounds = listOf(midpoint),
        )

        assertTrue(colorContrastRatio(corrected, midpoint) >= 4.5)
    }

    @Test
    fun transitionContrastCorrectionKeepsAccessibleColorUnchanged() {
        val foreground = Color.Black
        val background = Color.White

        assertEquals(
            foreground,
            ensureThemeContrast(foreground, listOf(background)),
        )
    }
}
