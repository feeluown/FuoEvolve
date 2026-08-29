package org.feeluown.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
                title = "当前设备暂不支持应用内更新",
                supportingText = "请通过应用发布页面获取新版本。",
            )
        }
        return
    }

    val operationBusy = update.phase == AppUpdatePhase.Checking ||
        update.phase == AppUpdatePhase.Downloading

    AppUpdateGroup(title = "更新版本") {
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
                    AppUpdateChannel.Stable -> "推荐日常使用，更新更稳定。"
                    AppUpdateChannel.Canary -> "更早体验新功能，但可能不够稳定。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AppUpdateDivider()
        AppUpdateRow(
            title = "自动检查更新",
            supportingText = "开启后会定期检查新版本。",
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

    AppUpdateGroup(title = "版本信息") {
        AppUpdateRow(
            title = "当前版本",
            trailingContent = {
                Text(
                    update.installedVersionName.ifBlank { "—" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
        )
        if (update.remoteVersionCode != null) {
            AppUpdateDivider()
            AppUpdateRow(
                title = "最新版本",
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

    AppUpdateGroup(title = "检查更新") {
        AppUpdateRow(title = appUpdateStatusText(update))
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
                    Text("立即更新")
                }
                AppUpdatePhase.InstallPermissionRequired -> Button(
                    onClick = controller::downloadAndInstallAppUpdate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("允许安装更新")
                }
                AppUpdatePhase.Installing -> OutlinedButton(
                    onClick = controller::downloadAndInstallAppUpdate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("重新打开安装界面")
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
                    Text(if (update.phase == AppUpdatePhase.Error) "重试" else "检查更新")
                }
            }
        }
    }

    update.releaseNotesUrl?.takeIf { it.isNotBlank() }?.let { releaseNotesUrl ->
        AppUpdateGroup(title = "更新内容") {
            AppUpdateRow(
                title = "查看更新说明",
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

    if (confirmCanary) {
        AlertDialog(
            onDismissRequest = { confirmCanary = false },
            title = { Text("切换到抢先体验版？") },
            text = {
                Text("可以更早体验新功能，但可能不够稳定。")
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
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    trailingContent: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val rowModifier = if (onClick == null) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick)
    }
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
        modifier = rowModifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
private fun AppUpdateDivider() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

private fun appUpdateStatusText(update: AppUpdateUiState): String = when (update.phase) {
    AppUpdatePhase.Idle -> "尚未检查更新"
    AppUpdatePhase.Checking -> "正在检查更新"
    AppUpdatePhase.UpToDate -> "当前已是最新版本"
    AppUpdatePhase.UpdateAvailable -> update.remoteVersionName
        ?.takeIf { it.isNotBlank() }
        ?.let { "发现新版本 $it" }
        ?: "发现新版本"
    AppUpdatePhase.Downloading -> "正在下载更新"
    AppUpdatePhase.InstallPermissionRequired -> "需要允许安装更新"
    AppUpdatePhase.Installing -> "请在系统界面完成安装"
    AppUpdatePhase.WaitingForStable -> "当前版本已经较新"
    AppUpdatePhase.Error -> "更新失败，请稍后重试"
}
