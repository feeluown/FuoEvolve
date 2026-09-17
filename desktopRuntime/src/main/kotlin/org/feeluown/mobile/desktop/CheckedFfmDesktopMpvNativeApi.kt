package org.feeluown.mobile.desktop

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.DesktopMpvNativeApi

fun createCheckedDesktopFfmMpvNativeApi(): DesktopMpvNativeApi {
    ensureEarlyPackagedResourcesDir()
    val delegate = createDesktopFfmMpvNativeApi()
    val nativeWindowsVideoHandles = ConcurrentHashMap.newKeySet<Long>()
    return object : DesktopMpvNativeApi by delegate {
        override fun setOption(handle: Long, name: String, value: String): Int {
            if (
                isWindowsDesktopRuntime() &&
                name == "vo" &&
                value == "libmpv"
            ) {
                val hwnd = desktopWindowsVideoHostHandle()
                val options = listOf(
                    "wid" to windowsMpvWidValue(hwnd),
                    "gpu-api" to "d3d11",
                    "gpu-context" to "d3d11",
                    "d3d11-output-mode" to "window",
                    "d3d11-flip" to "yes",
                    "vo" to "gpu-next",
                )
                for ((optionName, optionValue) in options) {
                    val result = delegate.setOption(handle, optionName, optionValue)
                    if (result < 0) return result
                }
                nativeWindowsVideoHandles += handle
                AppLogger.i(
                    "DesktopVideo",
                    "configured native Windows mpv output with gpu-next/d3d11",
                )
                return 0
            }

            // The previous libmpv/ANGLE pipeline forced d3d11-egl for direct hardware decode.
            // Native D3D11 output owns the D3D11 device itself, so leave hwdec interop on mpv's
            // automatic selection instead of forcing the OpenGL/EGL interop backend.
            if (
                name == "gpu-hwdec-interop" &&
                handle in nativeWindowsVideoHandles
            ) {
                return 0
            }
            return delegate.setOption(handle, name, value)
        }

        override fun destroy(handle: Long) {
            nativeWindowsVideoHandles.remove(handle)
            delegate.destroy(handle)
        }

        override fun renderD3D11(renderContext: Long, renderTarget: Long): Boolean {
            if (!delegate.renderD3D11(renderContext, renderTarget)) {
                throw IllegalStateException("Windows D3D11 libmpv render failed")
            }
            return true
        }
    }
}

private fun ensureEarlyPackagedResourcesDir() {
    if (!System.getProperty(APP_RESOURCES_DIR_PROPERTY).isNullOrBlank()) return
    val resourcesDir = resolvePackagedDesktopResourcesDir() ?: return
    System.setProperty(APP_RESOURCES_DIR_PROPERTY, resourcesDir.absolutePath)
}

internal fun resolvePackagedDesktopResourcesDir(
    osName: String = System.getProperty("os.name").orEmpty(),
    procSelfExe: File = File("/proc/self/exe"),
    executableCommand: String? = ProcessHandle.current().info().command().orElse(null),
): File? {
    val executableDir = if (osName.contains("linux", ignoreCase = true)) {
        runCatching {
            procSelfExe.takeIf(File::exists)?.canonicalFile?.parentFile
        }.getOrNull()
    } else {
        null
    } ?: executableCommand
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
        ?.absoluteFile
        ?.parentFile

    return executableDir?.takeIf { File(it, "native").isDirectory }
}

private const val APP_RESOURCES_DIR_PROPERTY = "compose.application.resources.dir"
