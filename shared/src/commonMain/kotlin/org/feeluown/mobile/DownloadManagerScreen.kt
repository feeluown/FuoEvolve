package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadManagerScreen(controller: FuoPlayerController) {
    var completedLimit by remember { mutableStateOf(5) }
    var pendingDelete by remember { mutableStateOf<DownloadTask?>(null) }
    var deleteFile by remember { mutableStateOf(true) }
    val tasks = controller.downloadTasks
    val active = tasks.filter { it.status != DownloadTaskStatus.Completed }
    val completed = tasks.filter { it.status == DownloadTaskStatus.Completed }
        .sortedByDescending { it.createdAt }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("下载管理") },
                navigationIcon = {
                    IconButton(onClick = controller::closeDownloadManager) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(paddingValues).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (active.isNotEmpty()) {
                item { DownloadSectionTitle("下载任务") }
                items(active, key = { it.id }) { task ->
                    DownloadTaskCard(
                        task = task,
                        onPause = { controller.pauseDownload(task.id) },
                        onResume = { controller.resumeDownload(task.id) },
                        onRetry = { controller.retryDownload(task.id) },
                        onDelete = { pendingDelete = task; deleteFile = true },
                    )
                }
            }
            item { DownloadSectionTitle("已完成") }
            if (completed.isEmpty()) {
                item { Text("暂无已完成下载", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
                items(completed.take(completedLimit), key = { it.id }) { task ->
                    DownloadTaskCard(
                        task = task,
                        onPause = {}, onResume = {}, onRetry = {},
                        onDelete = { pendingDelete = task; deleteFile = true },
                    )
                }
                if (completed.size > completedLimit) {
                    item {
                        TextButton(onClick = { completedLimit += 20 }) {
                            Text(if (completedLimit == 5) "展开更多" else "加载更多")
                        }
                    }
                }
            }
        }
    }
    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除下载") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(task.track.title.ifBlank { "该下载任务" })
                    if (task.status == DownloadTaskStatus.Completed) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = deleteFile, onCheckedChange = { deleteFile = it })
                            Text("同时删除本地文件")
                        }
                    } else {
                        Text("删除后将清理保留的临时文件，无法继续下载。")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    controller.deleteDownloadTask(task.id, deleteFile || task.status != DownloadTaskStatus.Completed)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun DownloadSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun DownloadTaskCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRetry: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(task.track.title.ifBlank { "未知歌曲" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(task.track.artists, downloadTaskStatusText(task)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (task.status == DownloadTaskStatus.Downloading) {
                    val progress = task.totalBytes?.takeIf { it > 0 }?.let { task.downloadedBytes.toFloat() / it }
                    if (progress != null) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                }
            }
            when (task.status) {
                DownloadTaskStatus.Downloading, DownloadTaskStatus.Queued -> IconButton(onClick = onPause) {
                    Icon(Icons.Filled.Pause, contentDescription = "暂停下载")
                }
                DownloadTaskStatus.Paused -> IconButton(onClick = onResume) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = "继续下载")
                }
                DownloadTaskStatus.Failed -> IconButton(onClick = onRetry) {
                    Icon(Icons.Filled.Refresh, contentDescription = "重试下载")
                }
                DownloadTaskStatus.Completed -> Unit
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除下载") }
        }
    }
}

private fun downloadTaskStatusText(task: DownloadTask): String = when (task.status) {
    DownloadTaskStatus.Queued -> "等待下载"
    DownloadTaskStatus.Downloading -> task.totalBytes?.let { "${formatBytes(task.downloadedBytes)} / ${formatBytes(it)}" } ?: "下载中"
    DownloadTaskStatus.Paused -> "已暂停，可继续"
    DownloadTaskStatus.Failed -> task.failureMessage ?: "下载失败"
    DownloadTaskStatus.Completed -> "已完成"
}
