package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
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
internal fun ProviderCredentialBackupOverlay(
    backup: AndroidProviderCredentialBackup,
    onImportFile: () -> Unit,
    onExportFile: (String) -> Unit,
    onRestored: (List<String>) -> Unit,
    onFeedback: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showActions by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }
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

    ExtendedFloatingActionButton(
        modifier = modifier,
        onClick = { showActions = true },
        icon = { androidx.compose.material3.Icon(Icons.Filled.Lock, contentDescription = null) },
        text = { Text("登录凭证备份") },
    )

    if (showActions) {
        AlertDialog(
            onDismissRequest = { showActions = false },
            title = { Text("登录凭证备份与恢复") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "备份会包含已登录音源的 Cookie、访问令牌和 OAuth 凭证。默认使用密码加密导出。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showActions = false
                            showExport = true
                        },
                    ) { Text("导出登录凭证") }
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            showActions = false
                            onImportFile()
                        },
                    ) { Text("从文件恢复") }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showActions = false }) { Text("取消") } },
        )
    }

    if (showExport) {
        ProviderCredentialExportDialog(
            backup = backup,
            onDismiss = { showExport = false },
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
                                    "，另有 ${result.ignoredProviderIds.size} 个当前版本不支持的音源已跳过"
                                onFeedback("已恢复 ${result.restoredProviderIds.size} 个音源登录凭证$ignored")
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
            title = { Text("登录凭证备份") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("确定") } },
        )
    }
}

@Composable
private fun ProviderCredentialExportDialog(
    backup: AndroidProviderCredentialBackup,
    onDismiss: () -> Unit,
    onExportFile: (String) -> Unit,
) {
    var mode by remember { mutableStateOf(ProviderCredentialExportMode.Encrypted) }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var plaintextRiskAccepted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val encryptedReady = password.length >= 8 && password == confirmation
    val canExport = !isBusy && when (mode) {
        ProviderCredentialExportMode.Encrypted -> encryptedReady
        ProviderCredentialExportMode.Plaintext -> plaintextRiskAccepted
    }

    AlertDialog(
        onDismissRequest = { if (!isBusy) onDismiss() },
        title = { Text("导出登录凭证") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("默认推荐加密备份；只有第三方应用明确需要读取凭证时才使用明文导出。")
                ExportModeRow(
                    selected = mode == ProviderCredentialExportMode.Encrypted,
                    title = "加密备份（推荐）",
                    description = "使用备份密码加密，可在其他设备恢复。",
                    onClick = {
                        mode = ProviderCredentialExportMode.Encrypted
                        plaintextRiskAccepted = false
                    },
                )
                ExportModeRow(
                    selected = mode == ProviderCredentialExportMode.Plaintext,
                    title = "明文 JSON（第三方应用）",
                    description = "不做任何加密，第三方程序可直接读取。",
                    onClick = { mode = ProviderCredentialExportMode.Plaintext },
                )

                if (mode == ProviderCredentialExportMode.Encrypted) {
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = { Text("备份密码（至少 8 位）") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    OutlinedTextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = confirmation,
                        onValueChange = { confirmation = it; error = null },
                        label = { Text("确认备份密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = confirmation.isNotEmpty() && confirmation != password,
                    )
                    Text(
                        "请妥善保存备份密码。密码不会写入文件，遗失后无法恢复加密备份。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("明文导出存在高风险", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "文件会直接包含 Cookie、访问令牌、Refresh Token、Authorization 以及 OAuth client_secret。任何拿到文件的人或应用都可能利用这些凭证访问你的账号。仅向你完全信任的第三方应用提供，不要上传到网盘、聊天、Issue 或其他公共位置，使用完成后请及时删除。",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
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
                            "我已了解明文导出的风险，并确认仍要继续",
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
    description: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.weight(1f).padding(top = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(2.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
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
                    Text("这是加密备份。恢复会覆盖备份中对应音源当前保存的登录凭证。")
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
                            "检测到明文凭证文件。文件内容未加密，恢复前请确认文件来自你信任的来源。",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    inspection.providerCount?.let { Text("备份包含 $it 个音源的登录凭证。") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                    Text("我确认覆盖备份中对应音源的现有登录凭证", Modifier.weight(1f))
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
