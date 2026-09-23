package org.feeluown.mobile

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class FuoVisualTokensTest {
    @Test
    fun refinedShapesUseSemanticHierarchy() {
        assertEquals(FuoVisualTokens.content, FuoVisualTokens.shapes.medium)
        assertEquals(FuoVisualTokens.group, FuoVisualTokens.shapes.large)
        assertEquals(FuoVisualTokens.overlay, FuoVisualTokens.shapes.extraLarge)
        assertNotEquals(FuoVisualTokens.artwork, FuoVisualTokens.floating)
    }

    @Test
    fun refinedTypographyEmphasizesTitlesWithoutReweightingBodyCopy() {
        assertEquals(FontWeight.Bold, FuoVisualTokens.typography.displayLarge.fontWeight)
        assertEquals(FontWeight.SemiBold, FuoVisualTokens.typography.headlineLarge.fontWeight)
        assertEquals(FontWeight.Medium, FuoVisualTokens.typography.titleMedium.fontWeight)
        assertEquals(androidx.compose.material3.Typography().bodyMedium, FuoVisualTokens.typography.bodyMedium)
    }

    @Test
    fun brandSeedKeepsTheExistingNamedPresetAndTouchTarget() {
        assertEquals(Color(0xFF246B43), FuoVisualTokens.brandSeed)
        assertEquals(themeSeedColor(ThemeColorScheme.FuoGreen), FuoVisualTokens.brandSeed)
        assertEquals(48.dp, FuoMinimumTouchTarget)
    }

    @Test
    fun surfaceRolesFollowEveryGeneratedColorScheme() {
        listOf(false, true).forEach { dark ->
            ThemeColorScheme.entries.filter { it != ThemeColorScheme.Dynamic }.forEach { preset ->
                val scheme = presetColorScheme(preset, darkTheme = dark)
                assertTrue(hasAccessibleContrast(scheme), "$preset dark=$dark")
                assertEquals(scheme.surface, FuoSurfaceColors.page(scheme))
                assertEquals(scheme.surfaceContainerLow, FuoSurfaceColors.section(scheme))
                assertEquals(scheme.surfaceContainer, FuoSurfaceColors.interactive(scheme))
                assertEquals(scheme.secondaryContainer, FuoSurfaceColors.selected(scheme))
                assertEquals(scheme.surfaceContainerHigh, FuoSurfaceColors.floating(scheme))
            }
        }
    }
}
