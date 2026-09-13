package org.feeluown.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

@Composable
internal fun DesktopProviderCredentialBackupHost(
    backupFactory: () -> ProviderCredentialBackup,
    availableProviders: () -> List<ProviderInfo>,
    refreshProviders: (List<ProviderInfo>) -> Unit,
    onFeedback: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var backup by remember { mutableStateOf<ProviderCredentialBackup?>(null) }
    var exportTarget by remember { mutableStateOf<ProviderCredentialBackupTarget?>(null) }

    fun ensureBackup(): ProviderCredentialBackup = backup ?: backupFactory().also { backup = it }

    val actions = ProviderCredentialBackupActions(
        exportAll = {
            ensureBackup()
            exportTarget = ProviderCredentialBackupTarget(
                providerId = null,
                providerName = "全部已登录音源",
            )
        },
        exportProvider = { provider ->
            ensureBackup()
            exportTarget = ProviderCredentialBackupTarget(
                providerId = provider.providerId,
                providerName = provider.providerName,
            )
        },
        importBackup = {
            scope.launch {
                openCredentialBackupFile(ensureBackup(), onFeedback)
            }
        },
    )

    CompositionLocalProvider(LocalProviderCredentialBackupActions provides actions) {
        content()
        backup?.let { activeBackup ->
            ProviderCredentialBackupDialogs(
                backup = activeBackup,
                exportTarget = exportTarget,
                onDismissExport = { exportTarget = null },
                onExportFile = { fileName ->
                    scope.launch {
                        saveCredentialBackupFile(activeBackup, fileName, onFeedback)
                    }
                },
                onRestored = { restoredProviderIds ->
                    val restored = restoredProviderIds.toSet()
                    refreshProviders(availableProviders().filter { it.providerId in restored })
                },
                onFeedback = onFeedback,
            )
        }
    }
}

private suspend fun openCredentialBackupFile(
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

private suspend fun saveCredentialBackupFile(
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
