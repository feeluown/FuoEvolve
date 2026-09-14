package org.feeluown.mobile

import java.io.File

internal fun configureDesktopWebLoginProcessEnvironment(
    environment: MutableMap<String, String>,
    sourceEnvironment: Map<String, String> = System.getenv(),
    resourcesDir: File? = desktopPackagedResourcesDir(),
) {
    val bundledLibraryDir = configuredDesktopRuntimePath(
        sourceEnvironment = sourceEnvironment,
        environmentName = "FUOEVOLVE_WEBVIEW_LIB_DIR",
        resourcesDir = resourcesDir,
        relativePath = "native/webview/lib",
        requireDirectory = true,
    )
    bundledLibraryDir?.let { libraryDir ->
        val inherited = environment["LD_LIBRARY_PATH"]?.takeIf(String::isNotBlank)
        environment["LD_LIBRARY_PATH"] = if (inherited == null) {
            libraryDir
        } else {
            "$libraryDir:$inherited"
        }
    }

    setDesktopWebLoginEnvironment(
        sourceEnvironment = sourceEnvironment,
        sourceName = "FUOEVOLVE_WEBKIT_EXEC_PATH",
        resourcesDir = resourcesDir,
        resourceRelativePath = "native/webview/webkit2gtk-4.1",
        targetEnvironment = environment,
        targetName = "WEBKIT_EXEC_PATH",
        requireDirectory = true,
    )
    setDesktopWebLoginEnvironment(
        sourceEnvironment = sourceEnvironment,
        sourceName = "FUOEVOLVE_WEBKIT_INJECTED_BUNDLE_PATH",
        resourcesDir = resourcesDir,
        resourceRelativePath = "native/webview/webkit2gtk-4.1/injected-bundle",
        targetEnvironment = environment,
        targetName = "WEBKIT_INJECTED_BUNDLE_PATH",
        requireDirectory = true,
    )
    setDesktopWebLoginEnvironment(
        sourceEnvironment = sourceEnvironment,
        sourceName = "FUOEVOLVE_GIO_EXTRA_MODULES",
        resourcesDir = resourcesDir,
        resourceRelativePath = "native/webview/gio/modules",
        targetEnvironment = environment,
        targetName = "GIO_EXTRA_MODULES",
        requireDirectory = true,
    )
}

private fun setDesktopWebLoginEnvironment(
    sourceEnvironment: Map<String, String>,
    sourceName: String,
    resourcesDir: File?,
    resourceRelativePath: String,
    targetEnvironment: MutableMap<String, String>,
    targetName: String,
    requireDirectory: Boolean,
) {
    configuredDesktopRuntimePath(
        sourceEnvironment = sourceEnvironment,
        environmentName = sourceName,
        resourcesDir = resourcesDir,
        relativePath = resourceRelativePath,
        requireDirectory = requireDirectory,
    )?.let { value -> targetEnvironment[targetName] = value }
}

private fun configuredDesktopRuntimePath(
    sourceEnvironment: Map<String, String>,
    environmentName: String,
    resourcesDir: File?,
    relativePath: String,
    requireDirectory: Boolean,
): String? {
    sourceEnvironment[environmentName]
        ?.takeIf(String::isNotBlank)
        ?.let { return it }

    val bundledPath = resourcesDir?.resolve(relativePath) ?: return null
    val usable = if (requireDirectory) bundledPath.isDirectory else bundledPath.exists()
    return bundledPath.absolutePath.takeIf { usable }
}

private fun desktopPackagedResourcesDir(): File? =
    System.getProperty("compose.application.resources.dir")
        ?.takeIf(String::isNotBlank)
        ?.let(::File)
