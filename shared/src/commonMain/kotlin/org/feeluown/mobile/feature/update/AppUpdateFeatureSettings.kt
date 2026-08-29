package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler

@Composable
internal fun AppUpdateFeatureSettings(
    state: SettingsFeatureUiState,
    controller: SettingsFeatureController,
) {
    val update = state.appUpdate
    val uriHandler = LocalUriHandler.current
    var confirmCanary by remember { mutableStateOf(false) }

    if (!update.supported) {
        AppUpdateGroup(title = "应用更新") {
            AppUpdateRow(
                title = "当前平台暂不支持应用内更新",
                supportingText = "可以继续通过系统或 FuoEvolve 发布页面更新应用。",
            )
        }
        return
    }

    val operationBusy = update.phase == AppUpdatePhase.Checking ||
        update.phase == AppUpdatePhase.Downloading

    AppUpdateGroup(title = "更新渠道") {
        Column(
            modifier = Modifier.fillMaxWidth().padding(FuoSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AppUpdateChannel.entries.forEachIndexed { index, channel ->
                    SegmentedButton(
                        selected = update.channel == channel,
                        enabled = !operationBusy,
                        onClick = {
                            if (channel == AppUpdateChannel.Canary && update.channel != AppUpdateChannel.Canary) {
                                confirmCanary = true
                            } else {
                                controller.setAppUpdateChannel(channel)
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index, AppUpdateChannel.entries.size),
                    ) {
                        Text(channel.label)
                    }
                }
            }
            Text(
                when (update.channel) {
                    AppUpdateChannel.Stable -> "仅接收正式发布版本，适合日常使用。"
                    AppUpdateChannel.Canary -> "跟随 master 最新成功构建，可能包含尚未稳定的改动。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppUpdateDivider()
        AppUpdateRow(
            title = "自动检查更新",
            supportingText = "应用启动后最多每 12 小时检查一次当前渠道；手动检查不受限制。",
            enabled = update.phase != AppUpdatePhase.Downloading,
            trailingContent = {
                Switch(
                    checked = update.autoCheckEnabled,
                    enabled = update.phase != AppUpdatePhase.Downloading,
                    onCheckedChange = controller::setAutoCheckAppUpdates,
                )
            },
        )
    }

    AppUpdateGroup(title = "版本") {
        AppUpdateRow(
            title = "当前版本",
            supportingText = "versionCode ${update.installedVersionCode}",
            trailingContent = {
                Text(
                    update.installedVersionName.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        update.remoteVersionCode?.let { remoteVersionCode ->
            AppUpdateDivider()
            AppUpdateRow(
                title = "${update.channel.label} 最新版本",
                supportingText = "versionCode $remoteVersionCode",
                trailingContent = {
                    Text(
                        update.remoteVersionName.orEmpty().ifBlank { "—" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
            )
        }
    }

    AppUpdateGroup(title = "更新状态") {
        AppUpdateRow(
            title = update.message ?: updatePhaseLabel(update.phase),
            supportingText = update.publishedAt?.takeIf { it.isNotBlank() }?.let { "发布时间 $it" },
        )
        if (update.phase == AppUpdatePhase.Downloading) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = FuoSpacing.lg, vertical = FuoSpacing.sm),
                verticalArrangement = Arrangement.spacedBy(FuoSpacing.xs),
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                val percentage = ((update.downloadProgress ?: 0f).coerceIn(0f, 1f) * 100f).toInt()
                Text(
                    "已下载 $percentage%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().padding(FuoSpacing.lg)) {
            when (update.phase) {
                AppUpdatePhase.UpdateAvailable -> Button(
                    onClick = controller::downloadAndInstallAppUpdate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null)
                    Text("下载并安装 ${update.remoteVersionName.orEmpty()}".trim())
                }
                AppUpdatePhase.InstallPermissionRequired -> Button(
                    onClick = controller::downloadAndInstallAppUpdate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("继续安装")
                }
                AppUpdatePhase.Installing -> OutlinedButton(
                    onClick = controller::downloadAndInstallAppUpdate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("再次打开安装器")
                }
                AppUpdatePhase.Checking -> Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("正在检查…")
                }
                AppUpdatePhase.Downloading -> Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("正在下载…")
                }
                else -> OutlinedButton(
                    onClick = controller::checkAppUpdates,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (update.phase == AppUpdatePhase.Error) "重新检查" else "检查更新")
                }
            }
        }
    }

    update.releaseNotesUrl?.takeIf { it.isNotBlank() }?.let { releaseNotesUrl ->
        AppUpdateGroup(title = "版本详情") {
            AppUpdateRow(
                title = if (update.channel == AppUpdateChannel.Stable) "查看发布说明" else "查看对应提交",
                supportingText = update.remoteVersionName,
                trailingContent = {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                onClick = { uriHandler.openUri(releaseNotesUrl) },
            )
        }
    }

    if (update.channel == AppUpdateChannel.Canary) {
        AppUpdateGroup(title = "Canary") {
            AppUpdateRow(
                title = "测试渠道",
                supportingText = "每次 master 成功构建都可能成为新版本。切回 Stable 时不会自动降级；若当前 Canary 比最新 Stable 新，会等待后续正式版追上。",
                titleColor = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (confirmCanary) {
        AlertDialog(
            onDismissRequest = { confirmCanary = false },
            title = { Text("切换到 Canary？") },
            text = {
                Text("Canary 跟随 master 最新成功构建，可能包含未完成或不稳定的改动。切换后会立即检查 Canary 更新。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmCanary = false
                        controller.setAppUpdateChannel(AppUpdateChannel.Canary)
                    },
                ) {
                    Text("切换")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCanary = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun AppUpdateGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FuoSpacing.sm)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = FuoSpacing.sm),
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge,
        ) {
            Column { content() }
        }
    }
}

@Composable
private fun AppUpdateRow(
    title: String,
    supportingText: String? = null,
    enabled: Boolean = true,
    titleColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(title, color = titleColor) },
        supportingContent = supportingText?.let { value ->
            {
                Text(
                    value,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = trailingContent,
        modifier = Modifier.fillMaxWidth().let { modifier ->
            if (onClick == null) modifier else modifier.then(
                androidx.compose.foundation.clickable(enabled = enabled, onClick = onClick),
            )
        },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}

@Composable
private fun AppUpdateDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun updatePhaseLabel(phase: AppUpdatePhase): String = when (phase) {
    AppUpdatePhase.Idle -> "尚未检查"
    AppUpdatePhase.Checking -> "正在检查更新"
    AppUpdatePhase.UpToDate -> "当前已是最新版本"
    AppUpdatePhase.UpdateAvailable -> "发现新版本"
    AppUpdatePhase.Downloading -> "正在下载更新"
    AppUpdatePhase.InstallPermissionRequired -> "需要安装未知来源应用权限"
    AppUpdatePhase.Installing -> "已打开系统安装器"
    AppUpdatePhase.WaitingForStable -> "等待后续 Stable 版本"
    AppUpdatePhase.Error -> "检查更新失败"
}
