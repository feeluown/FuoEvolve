package org.feeluown.mobile

/** Windows video controller whose mpv output is presented through a controller-owned parent HWND. */
interface DesktopWindowsNativeVideoController {
    val windowsNativeVideoHostHandle: Long
}
