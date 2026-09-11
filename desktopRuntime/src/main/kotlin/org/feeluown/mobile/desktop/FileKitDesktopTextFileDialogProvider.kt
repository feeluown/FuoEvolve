package org.feeluown.mobile.desktop

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.feeluown.mobile.DesktopTextFile
import org.feeluown.mobile.DesktopTextFileDialogProvider

/**
 * OS-native desktop Open/Save dialogs via FileKit. Nucleus ships GraalVM reachability metadata for
 * FileKit, so this path stays compatible with the Tao Native Image host without Swing/AWT dialogs.
 */
internal class FileKitDesktopTextFileDialogProvider : DesktopTextFileDialogProvider {
    override suspend fun openTextFile(
        dialogTitle: String,
        filterDescription: String,
        extensions: List<String>,
    ): DesktopTextFile? {
        val normalizedExtensions = normalizeExtensions(extensions)
        val picked = if (normalizedExtensions.isEmpty()) {
            FileKit.openFilePicker()
        } else {
            FileKit.openFilePicker(type = FileKitType.File(*normalizedExtensions.toTypedArray()))
        } ?: return null

        return withContext(Dispatchers.IO) {
            val path = Path.of(picked.path)
            DesktopTextFile(
                fileName = picked.name,
                content = Files.readString(path, Charsets.UTF_8),
            )
        }
    }

    override suspend fun saveTextFile(
        dialogTitle: String,
        suggestedFileName: String,
        filterDescription: String,
        extensions: List<String>,
        content: String,
    ): Boolean {
        val normalizedExtensions = normalizeExtensions(extensions)
        val defaultExtension = normalizedExtensions.firstOrNull()
        val suggestedName = suggestedFileName
            .removeSuffix(defaultExtension?.let { ".$it" }.orEmpty())
            .ifBlank { "playlist" }
        val picked = FileKit.openFileSaver(
            suggestedName = suggestedName,
            defaultExtension = defaultExtension,
            allowedExtensions = normalizedExtensions.takeIf { it.isNotEmpty() }?.toSet(),
        ) ?: return false

        withContext(Dispatchers.IO) {
            Files.writeString(Path.of(picked.path), content, Charsets.UTF_8)
        }
        return true
    }
}

private fun normalizeExtensions(extensions: List<String>): List<String> =
    extensions
        .map { extension -> extension.trim().removePrefix(".").lowercase() }
        .filter(String::isNotBlank)
        .distinct()
