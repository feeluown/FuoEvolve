package org.feeluown.mobile.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

private const val COMPOSE_APPLICATION_RESOURCES_DIR = "compose.application.resources.dir"

internal fun packagedDesktopResource(relativePath: String): Path? {
    val root = System.getProperty(COMPOSE_APPLICATION_RESOURCES_DIR)
        ?.takeIf(String::isNotBlank)
        ?: return null
    return Paths.get(root)
        .resolve(relativePath)
        .normalize()
        .takeIf(Files::isRegularFile)
}

internal fun packagedDesktopResourceDirectory(relativePath: String): Path? {
    val root = System.getProperty(COMPOSE_APPLICATION_RESOURCES_DIR)
        ?.takeIf(String::isNotBlank)
        ?: return null
    return Paths.get(root)
        .resolve(relativePath)
        .normalize()
        .takeIf(Files::isDirectory)
}

internal fun packagedMpvLibraryCandidates(): List<String> {
    val root = packagedDesktopResourceDirectory("native/mpv") ?: return emptyList()
    val names = when {
        com.sun.jna.Platform.isWindows() -> listOf("mpv-2.dll", "libmpv-2.dll", "mpv.dll")
        com.sun.jna.Platform.isMac() -> listOf("libmpv.dylib")
        else -> listOf("libmpv.so.2", "libmpv.so")
    }
    return names.mapNotNull { name ->
        root.resolve(name).takeIf(Files::isRegularFile)?.toAbsolutePath()?.toString()
    }
}
