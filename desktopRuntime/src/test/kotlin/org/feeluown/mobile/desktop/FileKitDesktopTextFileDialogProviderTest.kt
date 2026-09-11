package org.feeluown.mobile.desktop

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileKitDesktopTextFileDialogProviderTest {
    @Test
    fun nonLinuxPlatformsDoNotRequireDesktopPortal() {
        assertTrue(desktopNativeFileDialogAvailable("Windows 11") { false })
        assertTrue(desktopNativeFileDialogAvailable("Mac OS X") { false })
    }

    @Test
    fun linuxRequiresDesktopPortalForTaoNativeDialogs() {
        assertTrue(desktopNativeFileDialogAvailable("Linux") { true })
        assertFalse(desktopNativeFileDialogAvailable("Linux") { false })
    }
}
