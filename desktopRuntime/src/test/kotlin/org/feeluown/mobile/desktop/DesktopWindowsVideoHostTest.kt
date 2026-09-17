package org.feeluown.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DesktopWindowsVideoHostTest {
    @Test
    fun `mpv wid keeps the unsigned low 32 bits of HWND`() {
        assertEquals(
            "2596069104",
            windowsMpvWidValue(0x1234_5678_9ABC_DEF0L),
        )
        assertEquals(
            "4294967295",
            windowsMpvWidValue(-1L),
        )
    }

    @Test
    fun `Windows video host can be created and destroyed`() {
        if (!isWindowsDesktopRuntime()) return

        val hwnd = createDesktopWindowsVideoHostHandle()
        try {
            assertNotEquals(0L, hwnd)
        } finally {
            destroyDesktopWindowsVideoHost(hwnd)
        }
    }
}
