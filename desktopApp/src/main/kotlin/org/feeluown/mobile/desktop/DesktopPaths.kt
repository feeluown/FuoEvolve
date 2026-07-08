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

    val dataDir: File by lazy {
        val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("user.home"), ".local/share")
        File(base, "fuo-evolve").ensureDirectory()
    }

    val stateDir: File by lazy {
        val base = System.getenv("XDG_STATE_HOME")?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: File(System.getProperty("user.home"), ".local/state")
        File(base, "fuo-evolve").ensureDirectory()
    }

    val musicDir: File by lazy {
        File(System.getProperty("user.home"), "Music").ensureDirectory()
    }

    val feelUOwnDirs: List<File> by lazy {
        listOf(
            File(configDir, "feeluown"),
            File(dataDir, "feeluown"),
            File(stateDir, "feeluown"),
            File(cacheDir, "feeluown"),
        ).onEach { it.ensureDirectory() }
    }

    fun File.ensureDirectory(): File {
        if (!exists()) mkdirs()
        return this
    }
}
