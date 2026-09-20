package org.feeluown.mobile.nucleus

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals

class WindowsVideoClipRectTest {
    @Test
    fun fullyVisibleKeepsOriginalVideoSize() {
        assertEquals(
            WindowsVideoClipRect(0, 0, 640, 360),
            windowsVideoClipRect(
                full = Rect(10f, 20f, 650f, 380f),
                visible = Rect(10f, 20f, 650f, 380f),
                widthPx = 640,
                heightPx = 360,
            ),
        )
    }

    @Test
    fun scrollingClipsTopWithoutMovingVideoOrigin() {
        assertEquals(
            WindowsVideoClipRect(0, 120, 640, 360),
            windowsVideoClipRect(
                full = Rect(0f, -120f, 640f, 240f),
                visible = Rect(0f, 0f, 640f, 240f),
                widthPx = 640,
                heightPx = 360,
            ),
        )
    }

    @Test
    fun resizeClipsRightAndBottomEdges() {
        assertEquals(
            WindowsVideoClipRect(0, 0, 310, 220),
            windowsVideoClipRect(
                full = Rect(100f, 100f, 740f, 460f),
                visible = Rect(100f, 100f, 410f, 320f),
                widthPx = 640,
                heightPx = 360,
            ),
        )
    }

    @Test
    fun fullyScrolledOutProducesEmptyRegion() {
        assertEquals(
            WindowsVideoClipRect(0, 0, 0, 0),
            windowsVideoClipRect(
                full = Rect(0f, -500f, 640f, -140f),
                visible = Rect.Zero,
                widthPx = 640,
                heightPx = 360,
            ),
        )
    }

    @Test
    fun fractionalDpiCoordinatesRoundInward() {
        assertEquals(
            WindowsVideoClipRect(11, 6, 99, 79),
            windowsVideoClipRect(
                full = Rect(0f, 0f, 100f, 80f),
                visible = Rect(10.2f, 5.2f, 99.9f, 79.9f),
                widthPx = 100,
                heightPx = 80,
            ),
        )
    }

    @Test
    fun scrollOutAndBackDetachesAndReattachesNativeOverlay() {
        val full = Rect(0f, -120f, 640f, 240f)
        val views = listOf(
            Rect(0f, -120f, 640f, 240f),
            Rect(0f, 0f, 640f, 240f),
            Rect.Zero,
            Rect(0f, 180f, 640f, 240f),
        )
        assertEquals(
            listOf(true, true, false, true),
            views.map { visible ->
                windowsVideoClipRect(full, visible, 640, 360).hasVisibleArea
            },
        )
    }

    @Test
    fun subpixelIntersectionDoesNotLeaveClickableOverlay() {
        val clip = windowsVideoClipRect(
            full = Rect(0f, 0f, 100f, 100f),
            visible = Rect(99.1f, 0f, 99.9f, 100f),
            widthPx = 100,
            heightPx = 100,
        )
        assertEquals(WindowsVideoClipRect(0, 0, 0, 0), clip)
        assertEquals(false, clip.hasVisibleArea)
    }
}
