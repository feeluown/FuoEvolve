package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter

@Composable
internal fun DesktopProviderCredentialBackupHost(
    backup: ProviderCredentialBackup,
    availableProviders: () -> List<ProviderInfo>,
    refreshProviders: (List<ProviderInfo>) -> Unit,
    onFeedback: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    var exportTarget by remember { mutableStateOf<ProviderCredentialBackupTarget?>(null) }
    val actions = ProviderCredentialBackupActions(
        exportAll = {
            exportTarget = ProviderCredentialBackupTarget(
                providerId = null,
                providerName = "全部已登录音源",
            )
        },
        exportProvider = { provider ->
            exportTarget = ProviderCredentialBackupTarget(
                providerId = provider.providerId,
                providerName = provider.providerName,
            )
        },
        importBackup = {
            openCredentialBackupFile(backup, onFeedback)
        },
    )

    CompositionLocalProvider(LocalProviderCredentialBackupActions provides actions) {
        content()
        ProviderCredentialBackupDialogs(
            backup = backup,
            exportTarget = exportTarget,
            onDismissExport = { exportTarget = null },
            onExportFile = { fileName -> saveCredentialBackupFile(backup, fileName, onFeedback) },
            onRestored = { restoredProviderIds ->
                val restored = restoredProviderIds.toSet()
                refreshProviders(availableProviders().filter { it.providerId in restored })
            },
            onFeedback = onFeedback,
        )
    }
}

private fun openCredentialBackupFile(
    backup: ProviderCredentialBackup,
    onFeedback: (String) -> Unit,
) {
    val chooser = credentialFileChooser().apply {
        dialogTitle = "导入登录凭证"
        fileSelectionMode = JFileChooser.FILES_ONLY
    }
    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return

    runCatching { chooser.selectedFile.readText(Charsets.UTF_8) }
        .onSuccess { content ->
            if (content.isBlank()) onFeedback("无法读取登录凭证备份文件")
            else backup.stageImport(content)
        }
        .onFailure { onFeedback(it.message ?: "无法读取登录凭证备份文件") }
}

private fun saveCredentialBackupFile(
    backup: ProviderCredentialBackup,
    fileName: String,
    onFeedback: (String) -> Unit,
) {
    val pending = backup.consumePendingExport() ?: return
    val chooser = credentialFileChooser().apply {
        dialogTitle = "导出登录凭证"
        selectedFile = File(fileName)
    }
    if (chooser.showSaveDialog(null) != JFileChooser.APPROVE_OPTION) return

    val target = chooser.selectedFile
    if (target.exists()) {
        val overwrite = JOptionPane.showConfirmDialog(
            null,
            "${target.name} 已存在，是否覆盖？",
            "导出登录凭证",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.WARNING_MESSAGE,
        )
        if (overwrite != JOptionPane.YES_OPTION) return
    }

    runCatching { target.writeText(pending.content, Charsets.UTF_8) }
        .onSuccess { onFeedback("登录凭证备份已导出") }
        .onFailure { onFeedback(it.message ?: "导出登录凭证失败") }
}

private fun credentialFileChooser(): JFileChooser = JFileChooser().apply {
    fileFilter = FileNameExtensionFilter("FuoEvolve 登录凭证备份 (*.json)", "json")
}
