package org.feeluown.mobile

data class PlaylistMigrationBackgroundRequest(
    val taskId: String,
    val sourceTitle: String,
)

data class PlaylistMigrationBackgroundProgress(
    val taskId: String,
    val title: String,
    val detail: String,
    val completed: Int,
    val total: Int,
    val indeterminate: Boolean,
    val terminal: Boolean,
)

/**
 * Platform-owned durability hook. Common migration logic remains usable without a scheduler.
 * Each target maps this request to the OS-native user-visible background-work mechanism.
 */
private var playlistMigrationBackgroundScheduler: ((PlaylistMigrationBackgroundRequest) -> Unit)? = null

fun installPlaylistMigrationBackgroundScheduler(
    scheduler: ((PlaylistMigrationBackgroundRequest) -> Unit)?,
) {
    playlistMigrationBackgroundScheduler = scheduler
}

internal fun enqueuePlaylistMigrationBackground(task: PlaylistMigrationTask) {
    playlistMigrationBackgroundScheduler?.invoke(
        PlaylistMigrationBackgroundRequest(task.id, task.source.title),
    )
}

fun PlaylistMigrationTask.backgroundProgress(): PlaylistMigrationBackgroundProgress = when (phase) {
    MigrationPhase.Loading -> PlaylistMigrationBackgroundProgress(
        taskId = id,
        title = "正在迁移 $sourceTitle",
        detail = "正在读取歌曲 · 已读取 ${entries.size} 首",
        completed = 0,
        total = 0,
        indeterminate = true,
        terminal = false,
    )
    MigrationPhase.Matching -> {
        val completedCount = entries.count { it.status != MigrationTrackStatus.Pending }
        PlaylistMigrationBackgroundProgress(
            taskId = id,
            title = "正在迁移 $sourceTitle",
            detail = "正在匹配歌曲 · $completedCount/${entries.size}",
            completed = completedCount,
            total = entries.size.coerceAtLeast(1),
            indeterminate = entries.isEmpty(),
            terminal = false,
        )
    }
    MigrationPhase.Writing -> {
        val writable = entries.filter { it.selected != null && it.status != MigrationTrackStatus.Skipped }
        val completedCount = writable.count {
            it.status == MigrationTrackStatus.Added ||
                it.status == MigrationTrackStatus.Failed ||
                it.status == MigrationTrackStatus.Uncertain
        }
        PlaylistMigrationBackgroundProgress(
            taskId = id,
            title = "正在迁移 $sourceTitle",
            detail = "正在写入歌曲 · $completedCount/${writable.size}",
            completed = completedCount,
            total = writable.size.coerceAtLeast(1),
            indeterminate = writable.isEmpty(),
            terminal = false,
        )
    }
    MigrationPhase.Complete -> PlaylistMigrationBackgroundProgress(
        taskId = id,
        title = "歌单迁移完成",
        detail = "已迁移 $addedCount 首${if (skippedCount > 0) " · 跳过 $skippedCount 首" else ""}",
        completed = 1,
        total = 1,
        indeterminate = false,
        terminal = true,
    )
    MigrationPhase.Partial -> PlaylistMigrationBackgroundProgress(
        taskId = id,
        title = "歌单迁移需要处理",
        detail = "已迁移 $addedCount 首 · $failedCount 首待重试",
        completed = addedCount,
        total = (addedCount + failedCount).coerceAtLeast(1),
        indeterminate = false,
        terminal = true,
    )
    MigrationPhase.Review -> PlaylistMigrationBackgroundProgress(
        taskId = id,
        title = "歌单迁移等待确认",
        detail = "有 $unresolvedCount 首需要确认",
        completed = 0,
        total = 0,
        indeterminate = true,
        terminal = true,
    )
    MigrationPhase.Destination -> PlaylistMigrationBackgroundProgress(
        taskId = id,
        title = "歌单迁移等待确认",
        detail = "请选择目标歌单",
        completed = 0,
        total = 0,
        indeterminate = true,
        terminal = true,
    )
    MigrationPhase.Paused -> PlaylistMigrationBackgroundProgress(
        taskId = id,
        title = "歌单迁移已暂停",
        detail = "已迁移 $addedCount 首",
        completed = 0,
        total = 0,
        indeterminate = true,
        terminal = true,
    )
}

private val PlaylistMigrationTask.sourceTitle: String
    get() = source.title.ifBlank { "歌单" }
