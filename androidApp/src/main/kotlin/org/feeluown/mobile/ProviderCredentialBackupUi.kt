package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun ProviderCredentialBackupDialogs(
    backup: AndroidProviderCredentialBackup,
    exportTarget: ProviderCredentialBackupTarget?,
    onDismissExport: () -> Unit,
    onExportFile: (String) -> Unit,
    onRestored: (List<String>) -> Unit,
    onFeedback: (String) -> Unit,
) {
    val pendingImport by backup.pendingImportContent.collectAsState()
    var importInspection by remember { mutableStateOf<ProviderCredentialBackupInspection?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pendingImport) {
        val content = pendingImport
        if (content == null) {
            importInspection = null
            return@LaunchedEffect
        }
        runCatching { backup.inspect(content) }
            .onSuccess {
                importInspection = it
                importError = null
            }
            .onFailure {
                backup.clearPendingImport()
                importInspection = null
                importError = it.message ?: "无法读取备份文件"
            }
    }

    exportTarget?.let { target ->
        ProviderCredentialExportDialog(
            backup = backup,
            target = target,
            onDismiss = onDismissExport,
            onExportFile = onExportFile,
        )
    }

    pendingImport?.let { content ->
        importInspection?.let { inspection ->
            ProviderCredentialImportDialog(
                inspection = inspection,
                onDismiss = {
                    backup.clearPendingImport()
                    importInspection = null
                },
                onRestore = { password ->
                    scope.launch {
                        runCatching { backup.restore(content, password) }
                            .onSuccess { result ->
                                backup.clearPendingImport()
                                importInspection = null
                                onRestored(result.restoredProviderIds)
                                val ignored = if (result.ignoredProviderIds.isEmpty()) "" else
                                    "，${result.ignoredProviderIds.size} 个音源已跳过"
                                onFeedback("已恢复 ${result.restoredProviderIds.size} 个音源$ignored")
                            }
                            .onFailure { importError = it.message ?: "恢复登录凭证失败" }
                    }
                },
            )
        }
    }

    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("登录凭证") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("确定") } },
        )
    }
}

@Composable
private fun ProviderCredentialExportDialog(
    backup: AndroidProviderCredentialBackup,
    target: ProviderCredentialBackupTarget,
    onDismiss: () -> Unit,
    onExportFile: (String) -> Unit,
) {
    var mode by remember(target.providerId) { mutableStateOf(ProviderCredentialExportMode.Encrypted) }
    var password by remember(target.providerId) { mutableStateOf("") }
    var confirmation by remember(target.providerId) { mutableStateOf("") }
    var plaintextRiskAccepted by remember(target.providerId) { mutableStateOf(false) }
    var error by remember(target.providerId) { mutableStateOf<String?>(null) }
    var isBusy by remember(target.providerId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val encryptedReady = password.length >= 8 && password == confirmation
    val canExport = !isBusy && when (mode) {
        ProviderCredentialExportMode.Encrypted -> encryptedReady
        ProviderCredentialExportMode.Plaintext -> plaintextRiskAccepted
    }

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = { Text(if (target.providerId == null) "导出全部登录凭证" else "导出 ${target.providerName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExportModeRow(
                    selected = mode == ProviderCredentialExportMode.Encrypted,
                    title = "加密备份",
                    onClick = {
                        mode = ProviderCredentialExportMode.Encrypted
                        plaintextRiskAccepted = false
                    },
                )
                ExportModeRow(
                    selected = mode == ProviderCredentialExportMode.Plaintext,
                    title = "明文 JSON",
                    onClick = { mode = ProviderCredentialExportMode.Plaintext },
                )

                if (mode == ProviderCredentialExportMode.Encrypted) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text("密码（至少 8 位）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = confirmation,
                        onValueChange = { confirmation = it; error = null },
                        label = { Text("确认密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = confirmation.isNotEmpty() && confirmation != password,
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            "明文文件包含登录凭证，请仅用于可信应用。",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = plaintextRiskAccepted,
                            onCheckedChange = { plaintextRiskAccepted = it },
                        )
                        Text(
                            "我确认导出明文凭证",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canExport,
                onClick = {
                    isBusy = true
                    error = null
                    scope.launch {
                        runCatching {
                            backup.export(
                                mode = mode,
                                password = if (mode == ProviderCredentialExportMode.Encrypted) password else "",
                                providerId = target.providerId,
                            )
                        }.onSuccess { file ->
                            backup.stageExport(file)
                            isBusy = false
                            onDismiss()
                            onExportFile(file.fileName)
                        }.onFailure {
                            isBusy = false
                            error = it.message ?: "导出登录凭证失败"
                        }
                    }
                },
            ) { Text(if (isBusy) "正在生成…" else "导出") }
        },
        dismissButton = { TextButton(enabled = !isBusy, onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ExportModeRow(
    selected: Boolean,
    title: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(title, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ProviderCredentialImportDialog(
    inspection: ProviderCredentialBackupInspection,
    onDismiss: () -> Unit,
    onRestore: (String) -> Unit,
) {
    var password by remember(inspection) { mutableStateOf("") }
    var confirmed by remember(inspection) { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("恢复登录凭证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (inspection.encrypted) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("备份密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            "这是明文凭证文件，请确认来源可信。",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                    Text("覆盖现有登录凭证", Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = confirmed && (!inspection.encrypted || password.isNotBlank()),
                onClick = { onRestore(password) },
            ) { Text("恢复") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
