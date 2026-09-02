package org.feeluown.mobile

import java.nio.file.Path
import java.nio.file.Paths

/** Desktop-only directory policy. Common features receive stores/repositories, never OS paths. */
internal object DesktopAppDirectories {
    fun data(): Path {
        val home = System.getProperty("user.home").orEmpty()
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val base = when {
            osName.contains("win") -> System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
                ?: System.getenv("APPDATA")?.takeIf(String::isNotBlank)
                ?: "$home/AppData/Local"
            osName.contains("mac") -> "$home/Library/Application Support"
            else -> System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)
                ?: "$home/.local/share"
        }
        return Paths.get(base, "FuoEvolve")
    }

    fun state(): Path {
        val home = System.getProperty("user.home").orEmpty()
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val base = when {
            osName.contains("win") -> System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
                ?: System.getenv("APPDATA")?.takeIf(String::isNotBlank)
                ?: "$home/AppData/Local"
            osName.contains("mac") -> "$home/Library/Application Support"
            else -> System.getenv("XDG_STATE_HOME")?.takeIf(String::isNotBlank)
                ?: "$home/.local/state"
        }
        return Paths.get(base, "FuoEvolve")
    }
}
