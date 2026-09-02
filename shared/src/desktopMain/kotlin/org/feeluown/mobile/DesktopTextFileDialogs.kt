package org.feeluown.mobile

import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

internal data class DesktopTextFile(
    val fileName: String,
    val content: String,
)

/**
 * Desktop-only file picker boundary. Common features deal only in names/content; Swing remains at
 * the platform edge and therefore cannot leak into shared feature contracts.
 */
internal fun openDesktopTextFile(
    dialogTitle: String,
    filterDescription: String,
    extensions: List<String>,
    onFeedback: (String) -> Unit,
): DesktopTextFile? {
    val chooser = desktopTextFileChooser(filterDescription, extensions).apply {
        this.dialogTitle = dialogTitle
        fileSelectionMode = JFileChooser.FILES_ONLY
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return null

    return runCatching {
        val file = chooser.selectedFile
        DesktopTextFile(file.name, file.readText(Charsets.UTF_8))
    }.onFailure { onFeedback(it.message ?: "无法读取文件") }
        .getOrNull()
}

internal fun saveDesktopTextFile(
    dialogTitle: String,
    suggestedFileName: String,
    filterDescription: String,
    extensions: List<String>,
    content: String,
    onFeedback: (String) -> Unit,
): Boolean {
    val chooser = desktopTextFileChooser(filterDescription, extensions).apply {
        this.dialogTitle = dialogTitle
        selectedFile = File(suggestedFileName)
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return false

    val target = chooser.selectedFile.withDefaultExtension(extensions.firstOrNull())
    if (target.exists()) {
        val overwrite = JOptionPane.showConfirmDialog(
            null,
            "${target.name} 已存在，是否覆盖？",
            dialogTitle,
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        if (overwrite != JOptionPane.YES_OPTION) return false
    }

    return runCatching { target.writeText(content, Charsets.UTF_8) }
        .onFailure { onFeedback(it.message ?: "写入文件失败") }
        .isSuccess
}

private fun desktopTextFileChooser(
    filterDescription: String,
    extensions: List<String>,
): JFileChooser = JFileChooser().apply {
    val normalized = extensions.map { it.trim().removePrefix(".") }.filter(String::isNotBlank)
    if (normalized.isNotEmpty()) {
        fileFilter = FileNameExtensionFilter(filterDescription, *normalized.toTypedArray())
    }
}

private fun File.withDefaultExtension(extension: String?): File {
    val normalized = extension?.trim()?.removePrefix(".").orEmpty()
    if (normalized.isBlank() || this.extension.isNotBlank()) return this
    return File(parentFile, "$name.$normalized")
}
