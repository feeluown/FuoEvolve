package org.feeluown.mobile.desktop

import io.github.vinceglb.filekit.FileKit
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.openFilePicker
import io.github.vinceglb.filekit.dialogs.openFileSaver
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.path
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.feeluown.mobile.DesktopTextFile
import org.feeluown.mobile.DesktopTextFileDialogProvider

/**
 * OS-native desktop Open/Save dialogs via FileKit. Nucleus ships GraalVM reachability metadata for
 * FileKit, so this path stays compatible with the Tao Native Image host without Swing/AWT dialogs.
 */
internal class FileKitDesktopTextFileDialogProvider(
    private val requireNativeLinuxPortal: Boolean = false,
    private val osName: String = System.getProperty("os.name").orEmpty(),
    private val linuxPortalAvailable: () -> Boolean = ::linuxDesktopPortalAvailable,
) : DesktopTextFileDialogProvider {
    override suspend fun openTextFile(
        dialogTitle: String,
        filterDescription: String,
        extensions: List<String>,
    ): DesktopTextFile? {
        ensureNativeDialogAvailable()
        val normalizedExtensions = normalizeExtensions(extensions)
        val type = if (normalizedExtensions.isEmpty()) {
            FileKitType.File()
        } else {
            FileKitType.File(normalizedExtensions)
        }
        val picked = FileKit.openFilePicker(
            type = type,
            dialogSettings = FileKitDialogSettings(title = dialogTitle),
        ) ?: return null

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
        val destination = pickSavePath(dialogTitle, suggestedFileName, extensions) ?: return false
        withContext(Dispatchers.IO) {
            Files.writeString(destination, content, Charsets.UTF_8)
        }
        return true
    }

    override suspend fun saveFile(
        dialogTitle: String,
        suggestedFileName: String,
        filterDescription: String,
        extensions: List<String>,
        sourceFile: Path,
    ): String? {
        val destination = pickSavePath(dialogTitle, suggestedFileName, extensions) ?: return null
        withContext(Dispatchers.IO) {
            Files.copy(sourceFile, destination, StandardCopyOption.REPLACE_EXISTING)
        }
        return destination.toString()
    }

    private suspend fun pickSavePath(
        dialogTitle: String,
        suggestedFileName: String,
        extensions: List<String>,
    ): Path? {
        ensureNativeDialogAvailable()
        val normalizedExtensions = normalizeExtensions(extensions)
        val defaultExtension = normalizedExtensions.firstOrNull()
        val suggestedName = suggestedFileName
            .removeSuffix(defaultExtension?.let { ".$it" }.orEmpty())
            .ifBlank { "file" }
        val picked = FileKit.openFileSaver(
            suggestedName = suggestedName,
            defaultExtension = defaultExtension,
            allowedExtensions = normalizedExtensions.takeIf { it.isNotEmpty() }?.toSet(),
            dialogSettings = FileKitDialogSettings(title = dialogTitle),
        ) ?: return null
        return Path.of(picked.path)
    }

    private fun ensureNativeDialogAvailable() {
        if (!requireNativeLinuxPortal) return
        check(desktopNativeFileDialogAvailable(osName, linuxPortalAvailable)) {
            "系统文件选择器不可用，请安装并启动适合当前桌面环境的 xdg-desktop-portal 后重试"
        }
    }
}

internal fun desktopNativeFileDialogAvailable(
    osName: String,
    linuxPortalProbe: () -> Boolean,
): Boolean = !osName.lowercase(Locale.ROOT).contains("linux") || linuxPortalProbe()

private fun linuxDesktopPortalAvailable(): Boolean {
    if (System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) return false
    return runCatching {
        val process = ProcessBuilder(
            "busctl",
            "--user",
            "--no-pager",
            "status",
            "org.freedesktop.portal.Desktop",
        ).redirectErrorStream(true).start()
        val finished = process.waitFor(LINUX_PORTAL_PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching false
        }
        process.exitValue() == 0
    }.getOrDefault(false)
}

private fun normalizeExtensions(extensions: List<String>): List<String> =
    extensions
        .map { extension -> extension.trim().removePrefix(".").lowercase() }
        .filter(String::isNotBlank)
        .distinct()

private const val LINUX_PORTAL_PROBE_TIMEOUT_SECONDS = 1L
