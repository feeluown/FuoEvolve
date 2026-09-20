package org.feeluown.mobile.nucleus

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import dev.nucleusframework.window.tao.NativeView
import dev.nucleusframework.window.tao.nucleusHwndPlatformView
import kotlin.math.ceil
import kotlin.math.floor
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.DesktopMpvNativeApi
import org.feeluown.mobile.DesktopPlatformVideoController
import org.feeluown.mobile.DesktopPlatformVideoSurface
import org.feeluown.mobile.DesktopWindowsNativeVideoController
import org.feeluown.mobile.VideoPlaybackPayload
import org.feeluown.mobile.desktop.clipDesktopWindowsVideoHost
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

/** A Win32 window region, expressed in the original (unclipped) video HWND's local pixels. */
internal data class WindowsVideoClipRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val hasVisibleArea: Boolean get() = right > left && bottom > top
}

/**
 * Compose computes the window-clipped bounds, including scroll clipping ancestors. Keep the HWND
 * at its original position and size and express only the visible intersection as an HRGN.
 * Round inward so fractional DPI coordinates never leak a pixel into adjacent UI.
 */
internal fun windowsVideoClipRect(
    full: Rect,
    visible: Rect,
    widthPx: Int,
    heightPx: Int,
): WindowsVideoClipRect {
    val width = widthPx.coerceAtLeast(0)
    val height = heightPx.coerceAtLeast(0)
    val left = ceil(visible.left - full.left).toInt().coerceIn(0, width)
    val top = ceil(visible.top - full.top).toInt().coerceIn(0, height)
    val right = floor(visible.right - full.left).toInt().coerceIn(0, width)
    val bottom = floor(visible.bottom - full.top).toInt().coerceIn(0, height)
    return if (right > left && bottom > top) {
        WindowsVideoClipRect(left, top, right, bottom)
    } else {
        WindowsVideoClipRect(0, 0, 0, 0)
    }
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
            var nativeViewAttached by remember { mutableStateOf(false) }
            // Nucleus maintains a separate top-level DirectComposition overlay whose region is
            // the FULL NativeView frame, regardless of SetWindowRgn on our video HWND. Removing
            // the NativeView when fully outside the scroll/window viewport also removes its
            // overlay region and its offscreen controls/hit targets. Do not dispose the mpv
            // controller: it owns the HWND and playback continues until the viewport re-enters.
            Box(
                modifier = modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                    val clip = windowsVideoClipRect(
                        full = coordinates.boundsInWindow(clipBounds = false),
                        visible = coordinates.boundsInWindow(clipBounds = true),
                        widthPx = coordinates.size.width,
                        heightPx = coordinates.size.height,
                    )
                    if (!clip.hasVisibleArea && nativeViewAttached) {
                        // Hide before detaching, so the unparented WS_POPUP cannot flash outside
                        // the app while Nucleus removes its overlay and child HWND attachment.
                        clipDesktopWindowsVideoHost(hwnd, 0, 0, 0, 0)
                    }
                    if (nativeViewAttached != clip.hasVisibleArea) {
                        nativeViewAttached = clip.hasVisibleArea
                    }
                },
            ) {
                if (nativeViewAttached) {
                    NativeView(
                        factory = {
                            nucleusHwndPlatformView(
                                handle = { hwnd },
                                onDispose = { hideDesktopWindowsVideoHost(hwnd) },
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                        content = {
                            // NativeView first synchronizes the full host frame. Its child then
                            // applies the visible HRGN without shifting mpv's video origin.
                            Box(
                                Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                                    val clip = windowsVideoClipRect(
                                        full = coordinates.boundsInWindow(clipBounds = false),
                                        visible = coordinates.boundsInWindow(clipBounds = true),
                                        widthPx = coordinates.size.width,
                                        heightPx = coordinates.size.height,
                                    )
                                    clipDesktopWindowsVideoHost(
                                        hwnd,
                                        clip.left,
                                        clip.top,
                                        clip.right,
                                        clip.bottom,
                                    )
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

private fun isWindowsDesktopRuntime(): Boolean =
    System.getProperty("os.name").orEmpty().contains("windows", ignoreCase = true)
