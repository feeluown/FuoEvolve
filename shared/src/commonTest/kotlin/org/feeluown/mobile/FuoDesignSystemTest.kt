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
    fun layoutBreakpointsUseWindowSpaceInsteadOfOrientationAlone() {
        val phonePortrait = appLayoutInfoFor(maxWidth = 360.dp, maxHeight = 800.dp)
        val phoneLandscape = appLayoutInfoFor(maxWidth = 800.dp, maxHeight = 360.dp)
        val tabletPortrait = appLayoutInfoFor(maxWidth = 800.dp, maxHeight = 1280.dp)
        val expandedBoundary = appLayoutInfoFor(maxWidth = 840.dp, maxHeight = 600.dp)
        val shortExpanded = appLayoutInfoFor(maxWidth = 900.dp, maxHeight = 520.dp)
        val tabletLandscape = appLayoutInfoFor(maxWidth = 1280.dp, maxHeight = 800.dp)
        val desktopPortrait = appLayoutInfoFor(maxWidth = 900.dp, maxHeight = 1000.dp)
        val desktopWide = appLayoutInfoFor(maxWidth = 1600.dp, maxHeight = 1000.dp)

        assertEquals(AppWidthSizeClass.Compact, phonePortrait.widthSizeClass)
        assertEquals(AppHeightSizeClass.Medium, phonePortrait.heightSizeClass)
        assertFalse(phonePortrait.isLandscape)
        assertFalse(phonePortrait.useWideLayout)
        assertFalse(phonePortrait.usePersistentNavigation)
        assertFalse(phonePortrait.useFullPlayerTwoPane)
        assertEquals(3, phonePortrait.gridColumns)

        assertEquals(AppWidthSizeClass.Medium, phoneLandscape.widthSizeClass)
        assertEquals(AppHeightSizeClass.Compact, phoneLandscape.heightSizeClass)
        assertTrue(phoneLandscape.isLandscape)
        assertFalse(phoneLandscape.useWideLayout)
        assertFalse(phoneLandscape.usePersistentNavigation)
        assertFalse(phoneLandscape.useFullPlayerTwoPane)
        assertEquals(3, phoneLandscape.gridColumns)

        assertEquals(AppWidthSizeClass.Medium, tabletPortrait.widthSizeClass)
        assertEquals(AppHeightSizeClass.Expanded, tabletPortrait.heightSizeClass)
        assertFalse(tabletPortrait.useWideLayout)
        assertFalse(tabletPortrait.usePersistentNavigation)
        assertFalse(tabletPortrait.useFullPlayerTwoPane)
        assertEquals(5, tabletPortrait.gridColumns)

        assertEquals(AppWidthSizeClass.Expanded, expandedBoundary.widthSizeClass)
        assertTrue(expandedBoundary.useWideLayout)
        assertFalse(expandedBoundary.usePersistentNavigation)
        assertTrue(expandedBoundary.useFullPlayerTwoPane)

        assertEquals(AppWidthSizeClass.Expanded, shortExpanded.widthSizeClass)
        assertTrue(shortExpanded.useWideLayout)
        assertTrue(shortExpanded.usePersistentNavigation)
        assertFalse(shortExpanded.useFullPlayerTwoPane)

        assertEquals(AppWidthSizeClass.Large, tabletLandscape.widthSizeClass)
        assertTrue(tabletLandscape.isLandscape)
        assertTrue(tabletLandscape.useWideLayout)
        assertTrue(tabletLandscape.usePersistentNavigation)
        assertTrue(tabletLandscape.useFullPlayerTwoPane)
        assertEquals(7, tabletLandscape.gridColumns)

        assertEquals(AppWidthSizeClass.Expanded, desktopPortrait.widthSizeClass)
        assertFalse(desktopPortrait.isLandscape)
        assertTrue(desktopPortrait.useWideLayout)
        assertTrue(desktopPortrait.usePersistentNavigation)
        assertTrue(desktopPortrait.useFullPlayerTwoPane)

        assertEquals(AppWidthSizeClass.ExtraLarge, desktopWide.widthSizeClass)
        assertTrue(desktopWide.useWideLayout)
        assertEquals(8, desktopWide.gridColumns)
    }

    @Test
    fun themeModeResolutionHonorsExplicitModesAndSystemMode() {
        assertFalse(resolvedDarkTheme(ThemeMode.System, systemDark = false))
        assertTrue(resolvedDarkTheme(ThemeMode.System, systemDark = true))
        assertFalse(resolvedDarkTheme(ThemeMode.Light, systemDark = true))
        assertTrue(resolvedDarkTheme(ThemeMode.Dark, systemDark = false))
    }
}
