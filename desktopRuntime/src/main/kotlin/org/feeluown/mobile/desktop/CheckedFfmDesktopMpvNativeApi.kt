package org.feeluown.mobile.desktop

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.DesktopMpvNativeApi

fun createCheckedDesktopFfmMpvNativeApi(): DesktopMpvNativeApi {
    ensureEarlyPackagedResourcesDir()
    val delegate = createDesktopFfmMpvNativeApi()
    val nativeWindowsVideoHosts = ConcurrentHashMap<Long, Long>()
    val pendingWindowsVideoHost = ThreadLocal.withInitial { 0L }
    return object : DesktopMpvNativeApi by delegate {
        override fun setOption(handle: Long, name: String, value: String): Int {
            if (
                isWindowsDesktopRuntime() &&
                name == "vo" &&
                value == "libmpv"
            ) {
                val hwnd = nativeWindowsVideoHosts.computeIfAbsent(handle) {
                    createDesktopWindowsVideoHostHandle()
                }
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
                    if (result < 0) {
                        nativeWindowsVideoHosts.remove(handle, hwnd)
                        if (pendingWindowsVideoHost.get() == hwnd) {
                            pendingWindowsVideoHost.set(0L)
                        }
                        destroyDesktopWindowsVideoHost(hwnd)
                        return result
                    }
                }
                pendingWindowsVideoHost.set(hwnd)
                AppLogger.i(
                    "DesktopVideo",
                    "configured native Windows mpv output with gpu-next/d3d11 " +
                        "hwnd=0x${hwnd.toString(16)}",
                )
                return 0
            }

            // The previous libmpv/ANGLE pipeline forced d3d11-egl for direct hardware decode.
            // Native D3D11 output owns the D3D11 device itself, so leave hwdec interop on mpv's
            // automatic selection instead of forcing the OpenGL/EGL interop backend.
            if (
                name == "gpu-hwdec-interop" &&
                handle in nativeWindowsVideoHosts
            ) {
                return 0
            }
            return delegate.setOption(handle, name, value)
        }

        override fun claimWindowsNativeVideoHostHandle(): Long {
            val hwnd = pendingWindowsVideoHost.get()
            pendingWindowsVideoHost.set(0L)
            return hwnd
        }

        override fun destroy(handle: Long) {
            val hwnd = nativeWindowsVideoHosts.remove(handle) ?: 0L
            if (pendingWindowsVideoHost.get() == hwnd) {
                pendingWindowsVideoHost.set(0L)
            }
            try {
                delegate.destroy(handle)
            } finally {
                destroyDesktopWindowsVideoHost(hwnd)
            }
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
