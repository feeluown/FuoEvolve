package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** One route, six user-facing steps. All durable data belongs to the app-scoped controller. */
@Composable
fun PlaylistMigrationScreen(
    controller: PlaylistMigrationFeatureController,
    onBack: () -> Unit,
    initialTaskId: String? = null,
    initialTarget: PlaylistMigrationOpenTarget? = null,
    onPrepareBackgroundWork: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val tasks by controller.tasks.collectAsStateWithLifecycle()
    val sources by controller.sources.collectAsStateWithLifecycle()
    val targets by controller.targets.collectAsStateWithLifecycle()
    val playlists by controller.playlists.collectAsStateWithLifecycle()
    val alternatives by controller.searchResults.collectAsStateWithLifecycle()
    val busy by controller.busy.collectAsStateWithLifecycle()
    val error by controller.error.collectAsStateWithLifecycle()
    val routeTaskId = initialTaskId?.trim()?.takeIf { it.isNotEmpty() }
    val isRouteDetail = routeTaskId != null
    var taskId by rememberSaveable(routeTaskId) { mutableStateOf(routeTaskId) }
    var choosingSource by rememberSaveable { mutableStateOf(false) }
    var sourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourcePlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    var previousIds by remember { mutableStateOf<Set<String>?>(null) }
    var selectedPosition by rememberSaveable { mutableStateOf<Int?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var destinationName by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(controller) { controller.refreshProviders() }
    LaunchedEffect(routeTaskId, initialTarget) {
        if (routeTaskId != null) {
            taskId = routeTaskId
            choosingSource = false
            selectedPosition = null
            query = ""
        }
    }
    LaunchedEffect(sourceId) {
        sourceId?.let(controller::loadPlaylists)
        sourcePlaylistId = null
    }
    LaunchedEffect(tasks, previousIds) {
        val previous = previousIds ?: return@LaunchedEffect
        val created = tasks.firstOrNull { it.id !in previous } ?: return@LaunchedEffect
        taskId = created.id
        previousIds = null
        choosingSource = false
    }
    val task = tasks.firstOrNull { it.id == taskId }
    val sourcePlaylist = playlists.firstOrNull { it.id == sourcePlaylistId && it.providerId == sourceId }

    LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                TextButton(onClick = {
                    if (isRouteDetail) {
                        onBack()
                    } else if (task != null || choosingSource) {
                        taskId = null
                        choosingSource = false
                        selectedPosition = null
                    } else onBack()
                }) { Text("返回") }
                Spacer(Modifier.width(12.dp))
                Text("歌单迁移", style = MaterialTheme.typography.titleLarge)
            }
        }
        error?.let { message ->
            item {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Row(Modifier.padding(12.dp)) {
                        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = controller::clearError) { Text("知道了") }
                    }
                }
            }
        }
        when {
            task == null && !choosingSource -> {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("把歌单搬到其他平台", style = MaterialTheme.typography.headlineSmall)
                        Text("自动找歌，匹配不准可以自己调整。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = { choosingSource = true; controller.refreshProviders() }) { Text("开始迁移") }
                        Text("迁移记录", style = MaterialTheme.typography.titleMedium)
                    }
                }
                items(tasks, key = { it.id }) { entry ->
                    Card(onClick = { taskId = entry.id }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(entry.source.title, style = MaterialTheme.typography.titleMedium)
                            Text("${entry.source.providerName} → ${targets.firstOrNull { it.providerId == entry.targetProviderId }?.providerName ?: entry.targetProviderId}")
                            Text(entry.phase.userLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("已添加 ${entry.addedCount} 首 · 跳过 ${entry.skippedCount} 首")
                        }
                    }
                }
            }
            task == null -> {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("选择来源", style = MaterialTheme.typography.titleMedium)
                        sources.forEach { source ->
                            FilterChip(
                                selected = sourceId == source.providerId,
                                onClick = { sourceId = source.providerId },
                                label = { Text(source.providerName) },
                            )
                        }
                        if (sourceId != null) {
                            Text("选择歌单", style = MaterialTheme.typography.titleMedium)
                            playlists.forEach { playlist ->
                                FilterChip(
                                    selected = sourcePlaylistId == playlist.id,
                                    onClick = { sourcePlaylistId = playlist.id },
                                    label = { Text("${playlist.title} · ${playlist.trackCount ?: "?"} 首") },
                                )
                            }
                        }
                        Text("迁移到", style = MaterialTheme.typography.titleMedium)
                        targets.filter { it.providerId != sourceId }.forEach { target ->
                            FilterChip(
                                selected = targetId == target.providerId,
                                onClick = { targetId = target.providerId },
                                label = { Text(target.providerName) },
                            )
                        }
                        Button(
                            enabled = sourcePlaylist != null && targetId != null && !busy,
                            onClick = {
                                val selected = sourcePlaylist ?: return@Button
                                val target = targetId ?: return@Button
                                onPrepareBackgroundWork()
                                previousIds = tasks.mapTo(mutableSetOf()) { it.id }
                                controller.start(selected, target)
                            },
                        ) { Text("开始找歌") }
                    }
                }
            }
            task.phase == MigrationPhase.Review -> {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("检查匹配", style = MaterialTheme.typography.headlineSmall)
                        Text("${task.entries.count { it.status == MigrationTrackStatus.Matched }} 首已匹配 · ${task.unresolvedCount} 首待处理")
                        if (task.unresolvedCount > 0) {
                            OutlinedButton(onClick = { controller.skipUnresolved(task.id) }) { Text("跳过剩余待处理歌曲") }
                        }
                        Button(enabled = task.unresolvedCount == 0 && !busy, onClick = { controller.confirm(task.id) }) {
                            Text(if (task.unresolvedCount == 0) "下一步" else "还有 ${task.unresolvedCount} 首待处理")
                        }
                    }
                }
                items(task.entries, key = { it.position }) { entry ->
                    Card(onClick = { selectedPosition = if (selectedPosition == entry.position) null else entry.position },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${entry.source.title} · ${entry.source.artists}", style = MaterialTheme.typography.titleSmall)
                            Text(entry.selected?.let { "→ ${it.title} · ${it.artists}" } ?: "暂未找到")
                            Text(entry.status.userLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (selectedPosition == entry.position) {
                                HorizontalDivider()
                                entry.candidates.forEach { candidate ->
                                    TextButton(onClick = { controller.chooseMatch(task.id, entry.position, candidate.track); selectedPosition = null }) {
                                        Text("${candidate.track.title} · ${candidate.track.artists}")
                                    }
                                }
                                OutlinedTextField(
                                    value = query, onValueChange = { query = it },
                                    label = { Text("搜索其他歌曲") }, modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
                                )
                                OutlinedButton(enabled = query.isNotBlank(), onClick = { controller.searchAlternatives(task.id, query) }) { Text("搜索") }
                                alternatives.forEach { alternative ->
                                    TextButton(onClick = { controller.chooseMatch(task.id, entry.position, alternative.toMigrationTrack()); selectedPosition = null }) {
                                        Text("${alternative.title} · ${alternative.artists}")
                                    }
                                }
                                TextButton(onClick = { controller.skip(task.id, entry.position); selectedPosition = null }) { Text("跳过这首") }
                            }
                        }
                    }
                }
            }
            task.phase == MigrationPhase.Destination ||
                (task.phase == MigrationPhase.Paused && task.resumePhase == MigrationPhase.Destination) -> {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("保存到哪里？", style = MaterialTheme.typography.headlineSmall)
                        Text("${task.entries.count { it.status == MigrationTrackStatus.Matched }} 首将迁移")
                        if (!task.creationAttempted && task.phase == MigrationPhase.Destination) {
                            OutlinedTextField(value = destinationName, onValueChange = { destinationName = it },
                                label = { Text("新歌单名称") }, placeholder = { Text(task.source.title) }, modifier = Modifier.fillMaxWidth())
                            Button(enabled = !busy, onClick = {
                                onPrepareBackgroundWork()
                                controller.createDestination(task.id, destinationName.ifBlank { task.source.title })
                            }) { Text("创建并迁移") }
                        } else if (task.creationAttempted) {
                            Text("请确认歌单是否已创建，再从下方选择。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("选择已有歌单", style = MaterialTheme.typography.titleMedium)
                        OutlinedButton(onClick = { controller.loadPlaylists(task.targetProviderId) }) { Text("刷新歌单") }
                        playlists.filter { it.providerId == task.targetProviderId && it.isOwnedByCurrentUser != false }.forEach { playlist ->
                            OutlinedButton(
                                onClick = {
                                    onPrepareBackgroundWork()
                                    controller.chooseDestination(task.id, playlist)
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(playlist.title)
                            }
                        }
                        Text("没有目标歌单？请先在对应平台创建，再回来刷新。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            task.phase == MigrationPhase.Loading || task.phase == MigrationPhase.Matching || task.phase == MigrationPhase.Writing -> {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(task.phase.userLabel(), style = MaterialTheme.typography.headlineSmall)
                        val total = task.entries.size
                        val done = when (task.phase) {
                            MigrationPhase.Loading -> total
                            MigrationPhase.Matching -> task.entries.count { it.status != MigrationTrackStatus.Pending }
                            else -> task.entries.count {
                                it.status in setOf(
                                    MigrationTrackStatus.Added,
                                    MigrationTrackStatus.Skipped,
                                    MigrationTrackStatus.Failed,
                                    MigrationTrackStatus.Uncertain,
                                )
                            }
                        }
                        Text("$done / ${task.sourceLoaded.thenCount(total)}")
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { controller.pause(task.id) }) { Text("暂停") }
                        Text("离开页面不会丢失进度。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            else -> {
                item {
                    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(task.phase.userLabel(), style = MaterialTheme.typography.headlineSmall)
                        Text("已添加 ${task.addedCount} 首 · 跳过 ${task.skippedCount} 首 · 失败 ${task.failedCount} 首")
                        task.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        if (task.phase == MigrationPhase.Paused || task.phase == MigrationPhase.Partial) {
                            Button(onClick = {
                                onPrepareBackgroundWork()
                                controller.retry(task.id)
                            }) { Text("继续迁移") }
                        }
                        if (task.phase == MigrationPhase.Complete) Text("已完成", color = MaterialTheme.colorScheme.primary)
                    }
                }
                items(
                    task.entries.filter {
                        it.status in setOf(
                            MigrationTrackStatus.Added,
                            MigrationTrackStatus.Skipped,
                            MigrationTrackStatus.Failed,
                            MigrationTrackStatus.Uncertain,
                        )
                    },
                    key = { it.position },
                ) { entry ->
                    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(entry.source.title)
                            Text(entry.status.userLabel(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            entry.selected?.let { selected -> Text("→ ${selected.title} · ${selected.artists}") }
                            entry.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.padding(bottom = 24.dp)) }
    }
}

private fun MigrationPhase.userLabel(): String = when (this) {
    MigrationPhase.Loading -> "正在读取歌单"
    MigrationPhase.Matching -> "正在找歌"
    MigrationPhase.Review -> "检查匹配"
    MigrationPhase.Destination -> "选择歌单"
    MigrationPhase.Writing -> "正在迁移"
    MigrationPhase.Complete -> "迁移完成"
    MigrationPhase.Partial -> "部分歌曲未成功"
    MigrationPhase.Paused -> "已暂停 · 可继续"
}

private fun MigrationTrackStatus.userLabel(): String = when (this) {
    MigrationTrackStatus.Pending -> "待匹配"
    MigrationTrackStatus.Matched -> "已匹配"
    MigrationTrackStatus.NeedsReview -> "待确认"
    MigrationTrackStatus.Missing -> "未找到"
    MigrationTrackStatus.Skipped -> "已跳过"
    MigrationTrackStatus.Adding -> "正在添加"
    MigrationTrackStatus.Added -> "已添加"
    MigrationTrackStatus.Failed -> "添加失败"
    MigrationTrackStatus.Uncertain -> "待核对"
}

private fun Boolean.thenCount(count: Int): String = if (this) count.toString() else "…"
