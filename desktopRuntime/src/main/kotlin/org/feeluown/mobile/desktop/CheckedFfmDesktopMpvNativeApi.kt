package org.feeluown.mobile.desktop

import java.io.File
import org.feeluown.mobile.DesktopMpvNativeApi

fun createCheckedDesktopFfmMpvNativeApi(): DesktopMpvNativeApi {
    ensureEarlyPackagedResourcesDir()
    val delegate = createDesktopFfmMpvNativeApi()
    return object : DesktopMpvNativeApi by delegate {
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
