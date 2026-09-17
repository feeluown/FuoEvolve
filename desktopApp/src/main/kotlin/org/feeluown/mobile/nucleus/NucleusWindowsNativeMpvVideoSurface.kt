package org.feeluown.mobile.nucleus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import dev.nucleusframework.window.tao.NativeView
import dev.nucleusframework.window.tao.nucleusHwndPlatformView
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.DesktopMpvNativeApi
import org.feeluown.mobile.DesktopPlatformVideoController
import org.feeluown.mobile.DesktopPlatformVideoSurface
import org.feeluown.mobile.VideoPlaybackPayload
import org.feeluown.mobile.desktop.desktopWindowsVideoHostHandle
import org.feeluown.mobile.desktop.hideDesktopWindowsVideoHost

/**
 * Selects the Windows-native mpv HWND presentation path while leaving Linux/macOS on the existing
 * Nucleus GPU integrations. The previous Windows D3D11/TextureView implementation remains compiled
 * in [NucleusMpvVideoSurface], but is no longer installed on Windows.
 */
internal fun createNucleusMpvVideoSurface(
    nativeApi: DesktopMpvNativeApi,
): DesktopPlatformVideoSurface =
    if (isWindowsDesktopRuntime()) {
        NucleusWindowsNativeMpvVideoSurface()
    } else {
        NucleusMpvVideoSurface(nativeApi)
    }

private class NucleusWindowsNativeMpvVideoSurface : DesktopPlatformVideoSurface {
    @Composable
    override fun Content(
        controller: DesktopPlatformVideoController,
        payload: VideoPlaybackPayload?,
        modifier: Modifier,
    ) {
        val hwnd = remember { desktopWindowsVideoHostHandle() }
        if (hwnd == 0L) {
            LaunchedEffect(Unit) {
                AppLogger.e("DesktopVideo", "Windows native mpv video host HWND is unavailable")
            }
            Box(modifier.fillMaxSize())
            return
        }

        LaunchedEffect(hwnd) {
            AppLogger.i(
                "DesktopVideo",
                "using native Windows mpv D3D11 window output hwnd=0x${hwnd.toString(16)}",
            )
        }

        key(hwnd) {
            NativeView(
                factory = {
                    nucleusHwndPlatformView(
                        handle = { hwnd },
                        onDispose = { hideDesktopWindowsVideoHost(hwnd) },
                    )
                },
                modifier = modifier.fillMaxSize(),
            )
        }
    }
}

private fun isWindowsDesktopRuntime(): Boolean =
    System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)
