package org.feeluown.mobile.nucleus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.nucleusframework.window.tao.NativeView
import dev.nucleusframework.window.tao.nucleusHwndPlatformView
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.DesktopMpvNativeApi
import org.feeluown.mobile.DesktopPlatformVideoController
import org.feeluown.mobile.DesktopPlatformVideoSurface
import org.feeluown.mobile.DesktopWindowsNativeVideoController
import org.feeluown.mobile.VideoPlaybackPayload
import org.feeluown.mobile.desktop.hideDesktopWindowsVideoHost

/**
 * Selects the Windows-native mpv HWND presentation path while leaving Linux/macOS on the existing
 * Nucleus GPU integrations. Windows does not fall back to the old D3D11/TextureView pipeline.
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
        val hwnd = remember(controller) {
            (controller as? DesktopWindowsNativeVideoController)
                ?.windowsNativeVideoHostHandle
                ?: 0L
        }
        if (hwnd == 0L) {
            LaunchedEffect(controller) {
                AppLogger.e(
                    "DesktopVideo",
                    "Windows native mpv video host HWND is unavailable; " +
                        "controller=${controller.javaClass.name}; " +
                        "error=${controller.state.value.errorMessage ?: "none"}",
                )
            }
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("视频播放器初始化失败，请导出诊断日志", color = Color.White)
            }
            return
        }

        LaunchedEffect(hwnd) {
            AppLogger.i(
                "DesktopVideo",
                "using controller-scoped native Windows mpv D3D11 output " +
                    "hwnd=0x${hwnd.toString(16)}",
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
