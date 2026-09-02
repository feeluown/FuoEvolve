package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

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
    val file = openDesktopTextFile(
        dialogTitle = "导入登录凭证",
        filterDescription = "FuoEvolve 登录凭证备份 (*.json)",
        extensions = listOf("json"),
        onFeedback = onFeedback,
    ) ?: return
    if (file.content.isBlank()) onFeedback("无法读取登录凭证备份文件")
    else backup.stageImport(file.content)
}

private fun saveCredentialBackupFile(
    backup: ProviderCredentialBackup,
    fileName: String,
    onFeedback: (String) -> Unit,
) {
    val pending = backup.consumePendingExport() ?: return
    val saved = saveDesktopTextFile(
        dialogTitle = "导出登录凭证",
        suggestedFileName = fileName,
        filterDescription = "FuoEvolve 登录凭证备份 (*.json)",
        extensions = listOf("json"),
        content = pending.content,
        onFeedback = onFeedback,
    )
    if (saved) onFeedback("登录凭证备份已导出")
}
