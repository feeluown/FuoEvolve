package org.feeluown.mobile.desktop

import java.io.File

internal object DesktopPaths {
    val configDir: File by lazy {
        val base = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("user.home"), ".config")
        File(base, "fuo-evolve").ensureDirectory()
    }

    val cacheDir: File by lazy {
        val base = System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("user.home"), ".cache")
        File(base, "fuo-evolve").ensureDirectory()
    }

    val musicDir: File by lazy {
        File(System.getProperty("user.home"), "Music").ensureDirectory()
    }

    fun File.ensureDirectory(): File {
        if (!exists()) mkdirs()
        return this
    }
}
