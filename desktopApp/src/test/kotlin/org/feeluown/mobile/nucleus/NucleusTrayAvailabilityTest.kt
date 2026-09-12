package org.feeluown.mobile.nucleus

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NucleusTrayAvailabilityTest {
    @Test
    fun windowsAndMacCanRestoreFromTray() {
        assertTrue(nucleusTrayCanRestoreWindow(osName = "Windows 11"))
        assertTrue(nucleusTrayCanRestoreWindow(osName = "Mac OS X"))
    }

    @Test
    fun linuxRequiresStatusNotifierWatcher() {
        assertFalse(
            nucleusTrayCanRestoreWindow(
                osName = "Linux",
                linuxStatusNotifierProbe = { false },
            ),
        )
        assertTrue(
            nucleusTrayCanRestoreWindow(
                osName = "Linux",
                linuxStatusNotifierProbe = { true },
            ),
        )
    }

    @Test
    fun unsupportedPlatformKeepsWindowReachable() {
        assertFalse(nucleusTrayCanRestoreWindow(osName = "Plan 9"))
    }
}
