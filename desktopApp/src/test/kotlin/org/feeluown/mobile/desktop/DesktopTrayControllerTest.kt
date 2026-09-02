package org.feeluown.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopTrayControllerTest {
    @Test
    fun selectsNativeTrayBackendPerDesktopPlatform() {
        assertEquals(DesktopTrayBackend.WindowsAwt, desktopTrayBackend("Windows 11"))
        assertEquals(DesktopTrayBackend.MacAwt, desktopTrayBackend("Mac OS X"))
        assertEquals(DesktopTrayBackend.MacAwt, desktopTrayBackend("Darwin"))
        assertEquals(DesktopTrayBackend.LinuxStatusNotifier, desktopTrayBackend("Linux"))
        assertEquals(DesktopTrayBackend.Unsupported, desktopTrayBackend("FreeBSD"))
    }

    @Test
    fun hidesOnlyWhenTrayCanRestoreWindow() {
        assertEquals(DesktopCloseBehavior.HideToTray, desktopCloseBehavior(trayAvailable = true))
        assertEquals(DesktopCloseBehavior.KeepVisible, desktopCloseBehavior(trayAvailable = false))
    }

    @Test
    fun trayCreationAndCloseNeverFailWhenPlatformIntegrationIsUnavailable() {
        val controller = createDesktopTrayController(onShow = {}, onExit = {})
        controller.close()
    }
}
