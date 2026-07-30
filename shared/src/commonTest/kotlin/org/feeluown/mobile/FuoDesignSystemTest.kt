package org.feeluown.mobile

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FuoDesignSystemTest {
    @Test
    fun spacingScaleUsesTheSharedFourPointGrid() {
        assertEquals(4.dp, FuoSpacing.xs)
        assertEquals(8.dp, FuoSpacing.sm)
        assertEquals(12.dp, FuoSpacing.md)
        assertEquals(16.dp, FuoSpacing.lg)
        assertEquals(24.dp, FuoSpacing.xl)
        assertEquals(32.dp, FuoSpacing.xxl)
        assertEquals(48.dp, FuoMinimumTouchTarget)
    }

    @Test
    fun layoutBreakpointsSeparateCompactLandscapeAndWideModes() {
        val compact = appLayoutInfoFor(maxWidth = 360.dp, maxHeight = 800.dp)
        val landscapePhone = appLayoutInfoFor(maxWidth = 600.dp, maxHeight = 360.dp)
        val wide = appLayoutInfoFor(maxWidth = 840.dp, maxHeight = 600.dp)
        val desktop = appLayoutInfoFor(maxWidth = 1200.dp, maxHeight = 800.dp)

        assertFalse(compact.isLandscape)
        assertFalse(compact.useWideLayout)
        assertEquals(3, compact.gridColumns)
        assertTrue(landscapePhone.isLandscape)
        assertFalse(landscapePhone.useWideLayout)
        assertEquals(3, landscapePhone.gridColumns)
        assertTrue(wide.useWideLayout)
        assertEquals(5, wide.gridColumns)
        assertEquals(6, desktop.gridColumns)
    }

    @Test
    fun themeModeResolutionHonorsExplicitModesAndSystemMode() {
        assertFalse(resolvedDarkTheme(ThemeMode.System, systemDark = false))
        assertTrue(resolvedDarkTheme(ThemeMode.System, systemDark = true))
        assertFalse(resolvedDarkTheme(ThemeMode.Light, systemDark = true))
        assertTrue(resolvedDarkTheme(ThemeMode.Dark, systemDark = false))
    }
}
