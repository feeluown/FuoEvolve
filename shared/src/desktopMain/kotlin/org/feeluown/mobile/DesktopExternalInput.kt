package org.feeluown.mobile

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

internal const val DESKTOP_ACTIVATION_FOCUS = "fuoevolve-internal://focus"

/** Resolve only platform file IO here; playlist parsing/import remains in the common feature owner. */
internal fun readDesktopExternalPlaylist(input: String): DesktopTextFile? {
    val normalized = input.trim().trim('"')
    val path = when {
        normalized.startsWith("file:", ignoreCase = true) ->
            runCatching { Paths.get(URI(normalized)) }.getOrNull()
        "://" !in normalized -> runCatching { Paths.get(normalized) }.getOrNull()
        else -> null
    } ?: return null
    if (!path.isFuoPlaylistFile()) return null
    return DesktopTextFile(
        fileName = path.fileName.toString(),
        content = Files.readString(path, StandardCharsets.UTF_8),
    )
}

private fun Path.isFuoPlaylistFile(): Boolean =
    Files.isRegularFile(this) && fileName.toString().substringAfterLast('.', "").equals("fuo", ignoreCase = true)
