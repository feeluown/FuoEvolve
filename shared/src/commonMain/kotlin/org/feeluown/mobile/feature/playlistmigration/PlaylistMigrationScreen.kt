package org.feeluown.mobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** The UI owns only transient selections; the coordinator owns every durable checkpoint. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    val creatableProviderIds by controller.creatableProviderIds.collectAsStateWithLifecycle()
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
    var searchedPosition by rememberSaveable { mutableStateOf<Int?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var destinationName by rememberSaveable { mutableStateOf("") }
    var destinationPlaylistId by rememberSaveable(taskId) { mutableStateOf<String?>(null) }

    LaunchedEffect(controller) { controller.refreshProviders() }
    LaunchedEffect(routeTaskId, initialTarget) {
        if (routeTaskId != null) {
            taskId = routeTaskId
            choosingSource = false
            selectedPosition = null
            searchedPosition = null
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
    val chosenDestination = playlists.firstOrNull {
        it.providerId == task?.targetProviderId && it.id == destinationPlaylistId && it.isOwnedByCurrentUser != false
    }
    val pickingDestination = task?.phase == MigrationPhase.Destination ||
        (task?.phase == MigrationPhase.Paused && task.resumePhase == MigrationPhase.Destination)

    LaunchedEffect(task?.id, pickingDestination) {
        if (pickingDestination && task != null) {
            destinationPlaylistId = null
            controller.loadPlaylists(task.targetProviderId)
        }
    }

    fun goBack() {
        if (isRouteDetail) onBack()
        else if (task != null || choosingSource) {
            taskId = null
            choosingSource = false
            selectedPosition = null
        } else onBack()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("歌单迁移") },
                navigationIcon = {
                    AdaptiveDetailNavigationIcon {
                        IconButton(onClick = ::goBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
            )
        },
        bottomBar = {
            val label = when {
                task == null && !choosingSource && !isRouteDetail -> "开始迁移"
                task == null && choosingSource -> "开始找歌"
                task?.phase == MigrationPhase.Review -> "下一步 · 选择目标歌单"
                pickingDestination && chosenDestination != null -> "开始迁移"
                task?.phase == MigrationPhase.Paused && !pickingDestination -> "继续迁移"
                task?.phase == MigrationPhase.Partial -> "重试"
                else -> null
            }
            if (label != null) {
                Surface(tonalElevation = 2.dp, shadowElevation = 3.dp) {
                    Button(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                        enabled = !busy && when {
                            task == null && choosingSource -> sourcePlaylist != null && targetId != null
                            task?.phase == MigrationPhase.Review -> task.unresolvedCount == 0
                            pickingDestination -> chosenDestination != null
                            else -> true
                        },
                        onClick = {
                            when {
                                task == null && !choosingSource -> {
                                    choosingSource = true
                                    controller.refreshProviders()
                                }
                                task == null -> {
                                    val source = sourcePlaylist ?: return@Button
                                    val target = targetId ?: return@Button
                                    onPrepareBackgroundWork()
                                    previousIds = tasks.mapTo(mutableSetOf()) { it.id }
                                    controller.start(source, target)
                                }
                                task.phase == MigrationPhase.Review -> controller.confirm(task.id)
                                pickingDestination -> {
                                    val destination = chosenDestination ?: return@Button
                                    onPrepareBackgroundWork()
                                    controller.chooseDestination(task.id, destination)
                                }
                                task.phase == MigrationPhase.Paused || task.phase == MigrationPhase.Partial -> {
                                    onPrepareBackgroundWork()
                                    controller.retry(task.id)
                                }
                            }
                        },
                    ) { Text(label) }
                }
            }
        },
    ) { insets ->
        Box(
            modifier = Modifier.fillMaxSize().padding(insets),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
            error?.let { message ->
                item("error") {
                    MigrationPanel {
                        Text(message, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = controller::clearError) { Text("知道了") }
                    }
                }
            }
            when {
                task == null && isRouteDetail -> {
                    item("missing") {
                        MigrationPanel {
                            MigrationHeading("迁移记录", "未找到这条迁移记录", "记录可能已删除，请从迁移列表重新进入。")
                        }
                    }
                }
                task == null && !choosingSource -> {
                    item("intro") {
                        MigrationPanel {
                            MigrationStepRail(activeStep = 0)
                            MigrationHeading("", "换个地方，继续听", "选歌单，核对歌曲，再迁移。")
                        }
                    }
                    item("history") { MigrationHeading("", "迁移记录", null) }
                    if (tasks.isEmpty()) {
                        item("empty") { MigrationPanel { Text("还没有迁移记录", color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    }
                    items(tasks, key = { it.id }) { entry ->
                        Card(
                            onClick = { taskId = entry.id },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(entry.source.title, style = MaterialTheme.typography.titleMedium)
                                Text("${entry.source.providerName} → ${targets.firstOrNull { it.providerId == entry.targetProviderId }?.providerName ?: entry.targetProviderId}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                                Text(entry.phase.migrationLabel(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                if (entry.phase in setOf(MigrationPhase.Writing, MigrationPhase.Complete, MigrationPhase.Partial)) {
                                    Text("已迁移 ${entry.addedCount} 首 · 跳过 ${entry.skippedCount} 首", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                task == null -> {
                    item("steps") { MigrationStepRail(activeStep = 0) }
                    item("source-heading") { MigrationHeading("", "选择歌单", "选择来源、歌单和目标。") }
                    item("source-label") { Text("来源", style = MaterialTheme.typography.titleSmall) }
                    items(sources, key = { "source:${it.providerId}" }) { source ->
                        MigrationChoice(source.providerName, null, source.providerId == sourceId) {
                            sourceId = source.providerId
                        }
                    }
                    if (sourceId != null) {
                        item("playlist-label") { Text("歌单", style = MaterialTheme.typography.titleSmall) }
                        items(playlists.filter { it.providerId == sourceId }, key = { "playlist:${it.providerId}:${it.id}" }) { playlist ->
                            MigrationChoice(playlist.title, playlist.trackCount?.let { "$it 首" }, playlist.id == sourcePlaylistId) {
                                sourcePlaylistId = playlist.id
                            }
                        }
                    }
                    item("target-heading") { Text("目标", style = MaterialTheme.typography.titleSmall) }
                    items(targets.filter { it.providerId != sourceId }, key = { "target:${it.providerId}" }) { target ->
                        MigrationChoice(target.providerName, null, target.providerId == targetId) { targetId = target.providerId }
                    }
                }
                task.phase == MigrationPhase.Review -> {
                    item("steps") { MigrationStepRail(activeStep = 1) }
                    item("review-heading") {
                        MigrationHeading("", "核对歌曲", "已匹配 ${task.entries.count { it.status == MigrationTrackStatus.Matched }} · 待处理 ${task.unresolvedCount}")
                    }
                    if (task.unresolvedCount > 0) {
                        item("unresolved") {
                            MigrationPanel {
                                Text("还有待处理歌曲", style = MaterialTheme.typography.bodyMedium)
                                OutlinedButton(onClick = { controller.skipUnresolved(task.id) }, enabled = !busy) { Text("全部跳过") }
                            }
                        }
                    }
                    items(task.entries, key = { it.position }) { entry ->
                        val expanded = selectedPosition == entry.position
                        Card(
                            onClick = {
                                selectedPosition = if (expanded) null else entry.position
                                searchedPosition = null
                                query = ""
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = CardDefaults.cardColors(
                                containerColor = if (expanded) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
                            ),
                        ) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(entry.source.title, style = MaterialTheme.typography.titleMedium)
                                Text(entry.source.artists, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                                Text(entry.selected?.let { "匹配到：${it.title} · ${it.artists}" } ?: "未找到匹配歌曲", style = MaterialTheme.typography.bodyMedium)
                                Text(entry.status.migrationLabel(), color = if (entry.status in setOf(MigrationTrackStatus.NeedsReview, MigrationTrackStatus.Missing)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                                if (expanded) {
                                    HorizontalDivider()
                                    entry.candidates.forEach { candidate ->
                                        TextButton(onClick = { controller.chooseMatch(task.id, entry.position, candidate.track); selectedPosition = null }) {
                                            Text("${candidate.track.title} · ${candidate.track.artists}", maxLines = 2)
                                        }
                                    }
                                    OutlinedTextField(
                                        value = query, onValueChange = { query = it },
                                        label = { Text("搜索歌曲") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    )
                                    OutlinedButton(enabled = query.isNotBlank() && !busy, onClick = {
                                        searchedPosition = entry.position
                                        controller.searchAlternatives(task.id, query)
                                    }) { Text("搜索") }
                                    if (searchedPosition == entry.position) alternatives.forEach { alternative ->
                                        TextButton(onClick = {
                                            controller.chooseMatch(task.id, entry.position, alternative.toMigrationTrack())
                                            selectedPosition = null
                                        }) { Text("${alternative.title} · ${alternative.artists}", maxLines = 2) }
                                    }
                                    TextButton(onClick = { controller.skip(task.id, entry.position); selectedPosition = null }) { Text("跳过") }
                                } else {
                                    Text("点击切换匹配", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                pickingDestination -> {
                    item("destination-heading") {
                        MigrationStepRail(activeStep = 2)
                        MigrationHeading("", "选择目标歌单", null)
                    }
                    if (task.targetProviderId in creatableProviderIds && !task.creationAttempted && task.phase == MigrationPhase.Destination) {
                        item("create") {
                            MigrationPanel {
                                Text("新建歌单", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(
                                    value = destinationName, onValueChange = { destinationName = it },
                                    label = { Text("名称") }, placeholder = { Text(task.source.title) },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                                )
                                Button(enabled = !busy, onClick = {
                                    onPrepareBackgroundWork()
                                    controller.createDestination(task.id, destinationName.ifBlank { task.source.title })
                                }) { Text("新建并迁移") }
                            }
                        }
                    } else if (task.creationAttempted) {
                        item("creation-warning") { MigrationPanel { Text("请先确认创建结果，再选择歌单。") } }
                    } else {
                        item("creation-unsupported") { MigrationPanel { Text("暂不支持新建，请先创建，再刷新。") } }
                    }
                    item("existing-head") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("已有歌单", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = { controller.loadPlaylists(task.targetProviderId) }, enabled = !busy) { Text("刷新") }
                        }
                    }
                    items(playlists.filter { it.providerId == task.targetProviderId && it.isOwnedByCurrentUser != false },
                        key = { "destination:${it.providerId}:${it.id}" }) { playlist ->
                        MigrationChoice(playlist.title, playlist.trackCount?.let { "$it 首" }, playlist.id == destinationPlaylistId) {
                            destinationPlaylistId = playlist.id
                        }
                    }
                    item("destination-tip") { Text("找不到？先创建后刷新。", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
                }
                task.phase in setOf(MigrationPhase.Loading, MigrationPhase.Matching, MigrationPhase.Writing) -> {
                    item("progress") {
                        val progress = task.backgroundProgress()
                        MigrationPanel {
                            MigrationStepRail(activeStep = if (task.phase == MigrationPhase.Writing) 2 else 1)
                            MigrationHeading(
                                "",
                                if (task.phase == MigrationPhase.Writing) "正在迁移" else "正在找歌",
                                task.destination?.title ?: task.source.title,
                            )
                            PlaylistMigrationProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                            Text(progress.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Text("自动保存", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(onClick = { controller.pause(task.id) }, enabled = !busy) { Text("暂停") }
                        }
                    }
                }
                task.phase == MigrationPhase.Paused -> {
                    item("paused") {
                        val progress = task.backgroundProgress()
                        MigrationPanel {
                            MigrationStepRail(activeStep = if (task.resumePhase == MigrationPhase.Writing) 2 else 1)
                            MigrationHeading("", "已暂停", task.destination?.title ?: task.source.title)
                            PlaylistMigrationProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                            Text(progress.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            Text("自动保存", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                else -> {
                    item("result") {
                        MigrationPanel {
                            MigrationStepRail(activeStep = 2)
                            MigrationHeading("", "迁移结果", task.destination?.title ?: task.source.title)
                            Text(task.phase.migrationLabel(), style = MaterialTheme.typography.headlineSmall)
                            Text("已迁移 ${task.addedCount} · 已跳过 ${task.skippedCount}", style = MaterialTheme.typography.bodyLarge)
                            val failed = task.entries.count { it.status == MigrationTrackStatus.Failed }
                            val uncertain = task.entries.count { it.status == MigrationTrackStatus.Uncertain }
                            if (failed > 0 || uncertain > 0) {
                                Text("失败 $failed · 待核对 $uncertain", color = MaterialTheme.colorScheme.error)
                            }
                            task.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    items(task.entries.filter {
                        it.status in setOf(MigrationTrackStatus.Added, MigrationTrackStatus.Skipped, MigrationTrackStatus.Failed, MigrationTrackStatus.Uncertain)
                    }, key = { it.position }) { entry ->
                        MigrationPanel {
                            Text(entry.source.title, style = MaterialTheme.typography.titleSmall)
                            Text(entry.status.migrationLabel(), color = if (entry.status in setOf(MigrationTrackStatus.Failed, MigrationTrackStatus.Uncertain)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            entry.selected?.let { Text("→ ${it.title} · ${it.artists}", style = MaterialTheme.typography.bodySmall) }
                            entry.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            item("end") { Spacer(Modifier.height(16.dp)) }
        }
    }
}
}

@Composable
private fun MigrationStepRail(activeStep: Int) {
    val steps = listOf("选歌单", "核对歌曲", "迁移歌单")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        steps.forEachIndexed { index, label ->
            Surface(
                color = if (index == activeStep) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                },
                contentColor = if (index == activeStep) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = MaterialTheme.shapes.large,
            ) {
                Text(
                    text = "${index + 1} $label",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                )
            }
            if (index < steps.lastIndex) {
                HorizontalDivider(
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

@Composable
private fun MigrationPanel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.extraLarge,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun MigrationHeading(step: String, title: String, subtitle: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (step.isNotBlank()) Text(step, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MigrationChoice(title: String, subtitle: String?, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                subtitle?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

private fun MigrationPhase.migrationLabel(): String = when (this) {
    MigrationPhase.Loading -> "正在读取歌单"
    MigrationPhase.Matching -> "正在找歌"
    MigrationPhase.Review -> "待核对"
    MigrationPhase.Destination -> "等待选择目标歌单"
    MigrationPhase.Writing -> "正在迁移"
    MigrationPhase.Complete -> "已完成"
    MigrationPhase.Partial -> "部分完成"
    MigrationPhase.Paused -> "已暂停 · 可继续"
}

private fun MigrationTrackStatus.migrationLabel(): String = when (this) {
    MigrationTrackStatus.Pending -> "待匹配"
    MigrationTrackStatus.Matched -> "已匹配"
    MigrationTrackStatus.NeedsReview -> "待确认"
    MigrationTrackStatus.Missing -> "未找到"
    MigrationTrackStatus.Skipped -> "已跳过"
    MigrationTrackStatus.Adding -> "正在迁移"
    MigrationTrackStatus.Added -> "已迁移"
    MigrationTrackStatus.Failed -> "迁移失败"
    MigrationTrackStatus.Uncertain -> "待核对"
}
