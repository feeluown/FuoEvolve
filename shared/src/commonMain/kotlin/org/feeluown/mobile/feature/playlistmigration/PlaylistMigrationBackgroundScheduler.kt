package org.feeluown.mobile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

enum class PlaylistMigrationBackgroundStage {
    Conversion,
    Writing,
}

data class PlaylistMigrationBackgroundRequest(
    val taskId: String,
    val sourceTitle: String,
    val stage: PlaylistMigrationBackgroundStage,
)

data class PlaylistMigrationBackgroundProgress(
    val taskId: String,
    val stage: PlaylistMigrationBackgroundStage,
    val title: String,
    val detail: String,
    val completed: Int,
    val total: Int,
    val indeterminate: Boolean,
    val terminal: Boolean,
    val openTarget: PlaylistMigrationOpenTarget?,
)

private data class PlaylistMigrationBackgroundRunnerBinding(
    val controller: PlaylistMigrationFeatureController,
    val scope: CoroutineScope,
)

/**
 * Platform-owned durability hook. Common migration logic remains usable without a scheduler.
 * Each target maps this request to the OS-native user-visible background-work mechanism.
 */
private var playlistMigrationBackgroundScheduler: ((PlaylistMigrationBackgroundRequest) -> Unit)? = null
private var playlistMigrationBackgroundRunner: PlaylistMigrationBackgroundRunnerBinding? = null

fun installPlaylistMigrationBackgroundScheduler(
    scheduler: ((PlaylistMigrationBackgroundRequest) -> Unit)?,
) {
    playlistMigrationBackgroundScheduler = scheduler
}

internal fun bindPlaylistMigrationBackgroundRunner(
    controller: PlaylistMigrationFeatureController,
    scope: CoroutineScope,
) {
    playlistMigrationBackgroundRunner = PlaylistMigrationBackgroundRunnerBinding(controller, scope)
}

internal fun PlaylistMigrationTask.backgroundStageOrNull(): PlaylistMigrationBackgroundStage? = when (phase) {
    MigrationPhase.Loading,
    MigrationPhase.Matching -> PlaylistMigrationBackgroundStage.Conversion
    MigrationPhase.Writing -> PlaylistMigrationBackgroundStage.Writing
    else -> null
}

internal fun PlaylistMigrationBackgroundStage.accepts(phase: MigrationPhase): Boolean = when (this) {
    PlaylistMigrationBackgroundStage.Conversion ->
        phase == MigrationPhase.Loading || phase == MigrationPhase.Matching
    PlaylistMigrationBackgroundStage.Writing -> phase == MigrationPhase.Writing
}

/** Returns true when an OS-owned scheduler accepted responsibility for executing the stage. */
internal fun enqueuePlaylistMigrationBackground(task: PlaylistMigrationTask): Boolean {
    val scheduler = playlistMigrationBackgroundScheduler ?: return false
    val stage = task.backgroundStageOrNull() ?: return false
    scheduler(PlaylistMigrationBackgroundRequest(task.id, task.source.title, stage))
    return true
}

/** Callback-shaped API for native platform schedulers such as iOS BackgroundTasks. */
fun runPlaylistMigrationBackgroundSlice(
    taskId: String,
    stage: String,
    maxSteps: Int,
    onProgress: (PlaylistMigrationBackgroundProgress) -> Unit,
    completionHandler: (Boolean, String?) -> Unit,
) {
    val parsedStage = runCatching { PlaylistMigrationBackgroundStage.valueOf(stage) }.getOrNull()
    if (parsedStage == null) {
        completionHandler(false, "无效的迁移后台阶段")
        return
    }
    val binding = playlistMigrationBackgroundRunner
    if (binding == null) {
        completionHandler(false, "迁移服务尚未初始化")
        return
    }
    binding.scope.launch {
        try {
            val needsContinuation = binding.controller.runBackgroundSlice(
                taskId = taskId,
                stage = parsedStage,
                maxSteps = maxSteps.coerceAtLeast(1),
                onProgress = { task -> onProgress(task.backgroundProgress(parsedStage)) },
            )
            completionHandler(needsContinuation, null)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            completionHandler(false, failure.message ?: "迁移失败")
        }
    }
}

/** Platform cancellation is persisted as a normal checkpoint-safe pause. */
fun pausePlaylistMigrationFromBackground(
    taskId: String,
    completionHandler: (String?) -> Unit,
) {
    val binding = playlistMigrationBackgroundRunner
    if (binding == null) {
        completionHandler("迁移服务尚未初始化")
        return
    }
    binding.scope.launch {
        try {
            binding.controller.coordinator.pause(taskId)
            completionHandler(null)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            completionHandler(failure.message ?: "暂停迁移失败")
        }
    }
}

fun PlaylistMigrationTask.backgroundProgress(
    expectedStage: PlaylistMigrationBackgroundStage? = backgroundStageOrNull(),
): PlaylistMigrationBackgroundProgress = when (phase) {
    MigrationPhase.Loading -> PlaylistMigrationBackgroundProgress(
        taskId = id,
        stage = PlaylistMigrationBackgroundStage.Conversion,
        title = "正在转换 $sourceTitle",
        detail = "正在读取歌曲 · 已读取 ${entries.size} 首",
        completed = 0,
        total = 0,
        indeterminate = true,
        terminal = false,
        openTarget = null,
    )
    MigrationPhase.Matching -> {
        val completedCount = entries.count { it.status != MigrationTrackStatus.Pending }
        PlaylistMigrationBackgroundProgress(
            taskId = id,
            stage = PlaylistMigrationBackgroundStage.Conversion,
            title = "正在转换 $sourceTitle",
            detail = "正在匹配歌曲 · $completedCount/${entries.size}",
            completed = completedCount,
            total = entries.size.coerceAtLeast(1),
            indeterminate = entries.isEmpty(),
            terminal = false,
            openTarget = null,
        )
    }
    MigrationPhase.Review -> PlaylistMigrationBackgroundProgress(
        taskId = id,
        stage = PlaylistMigrationBackgroundStage.Conversion,
        title = "歌曲转换完成",
        detail = if (unresolvedCount > 0) {
            "有 $unresolvedCount 首需要确认，点击检查转换结果"
        } else {
            "请确认转换结果，点击继续"
        },
        completed = entries.size,
        total = entries.size.coerceAtLeast(1),
        indeterminate = false,
        terminal = true,
        openTarget = PlaylistMigrationOpenTarget.Review,
    )
    MigrationPhase.Destination -> PlaylistMigrationBackgroundProgress(
        taskId = id,
        stage = PlaylistMigrationBackgroundStage.Conversion,
        title = "转换结果已确认",
        detail = "请选择目标歌单",
        completed = entries.size,
        total = entries.size.coerceAtLeast(1),
        indeterminate = false,
        terminal = true,
        openTarget = PlaylistMigrationOpenTarget.Review,
    )
    MigrationPhase.Writing -> {
        val writable = entries.filter { it.selected != null && it.status != MigrationTrackStatus.Skipped }
        val completedCount = writable.count {
            it.status == MigrationTrackStatus.Added ||
                it.status == MigrationTrackStatus.Failed ||
                it.status == MigrationTrackStatus.Uncertain
        }
        PlaylistMigrationBackgroundProgress(
            taskId = id,
            stage = PlaylistMigrationBackgroundStage.Writing,
            title = "正在写入目标歌单",
            detail = "正在写入歌曲 · $completedCount/${writable.size}",
            completed = completedCount,
            total = writable.size.coerceAtLeast(1),
            indeterminate = writable.isEmpty(),
            terminal = false,
            openTarget = null,
        )
    }
    MigrationPhase.Complete -> PlaylistMigrationBackgroundProgress(
        taskId = id,
        stage = PlaylistMigrationBackgroundStage.Writing,
        title = "目标歌单写入完成",
        detail = "已写入 $addedCount 首${if (skippedCount > 0) " · 跳过 $skippedCount 首" else ""}，点击查看结果",
        completed = entries.count { it.selected != null && it.status != MigrationTrackStatus.Skipped },
        total = entries.count { it.selected != null && it.status != MigrationTrackStatus.Skipped }.coerceAtLeast(1),
        indeterminate = false,
        terminal = true,
        openTarget = PlaylistMigrationOpenTarget.Result,
    )
    MigrationPhase.Partial -> PlaylistMigrationBackgroundProgress(
        taskId = id,
        stage = PlaylistMigrationBackgroundStage.Writing,
        title = "目标歌单写入需要处理",
        detail = "已写入 $addedCount 首 · $failedCount 首待处理，点击查看结果",
        completed = addedCount,
        total = (addedCount + failedCount).coerceAtLeast(1),
        indeterminate = false,
        terminal = true,
        openTarget = PlaylistMigrationOpenTarget.Result,
    )
    MigrationPhase.Paused -> {
        val stage = when (resumePhase) {
            MigrationPhase.Writing -> PlaylistMigrationBackgroundStage.Writing
            else -> expectedStage ?: PlaylistMigrationBackgroundStage.Conversion
        }
        PlaylistMigrationBackgroundProgress(
            taskId = id,
            stage = stage,
            title = if (stage == PlaylistMigrationBackgroundStage.Writing) "目标歌单写入已暂停" else "歌曲转换已暂停",
            detail = if (stage == PlaylistMigrationBackgroundStage.Writing) {
                "已写入 $addedCount 首，点击查看"
            } else {
                "已保存转换进度，点击查看"
            },
            completed = 0,
            total = 0,
            indeterminate = true,
            terminal = true,
            openTarget = if (stage == PlaylistMigrationBackgroundStage.Writing) {
                PlaylistMigrationOpenTarget.Result
            } else {
                PlaylistMigrationOpenTarget.Review
            },
        )
    }
}

private val PlaylistMigrationTask.sourceTitle: String
    get() = source.title.ifBlank { "歌单" }
