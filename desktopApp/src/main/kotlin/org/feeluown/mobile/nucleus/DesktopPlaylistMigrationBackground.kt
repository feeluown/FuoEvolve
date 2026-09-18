package org.feeluown.mobile.nucleus

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import dev.nucleusframework.application.NucleusDecoratedWindowScope
import dev.nucleusframework.notification.common.NotificationManager
import dev.nucleusframework.notification.common.notification
import dev.nucleusframework.taskbarprogress.tao.hideTaskbarProgress
import dev.nucleusframework.taskbarprogress.tao.requestTaskbarAttention
import dev.nucleusframework.taskbarprogress.tao.showTaskbarError
import dev.nucleusframework.taskbarprogress.tao.showTaskbarIndeterminate
import dev.nucleusframework.taskbarprogress.tao.showTaskbarProgress
import java.util.LinkedHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.feeluown.mobile.AppLogger
import org.feeluown.mobile.PlaylistMigrationBackgroundProgress
import org.feeluown.mobile.PlaylistMigrationBackgroundRequest
import org.feeluown.mobile.PlaylistMigrationOpenTarget
import org.feeluown.mobile.installPlaylistMigrationBackgroundScheduler
import org.feeluown.mobile.openPlaylistMigrationFromNotification
import org.feeluown.mobile.runPlaylistMigrationBackgroundSlice

/**
 * Desktop migration runner backed by Nucleus' native taskbar/Dock/Launcher progress facilities.
 * Conversion and writing are keyed independently so Review remains a hard user-confirmation gate.
 */
@Composable
internal fun NucleusDecoratedWindowScope.PlaylistMigrationBackgroundHost(
    uiScope: CoroutineScope,
    showWindow: () -> Unit,
) {
    val scheduler = remember(nucleusWindow, uiScope) {
        DesktopPlaylistMigrationBackgroundScheduler(
            onVisibleProgressChanged = { progress ->
                uiScope.launch {
                    when {
                        progress == null -> nucleusWindow.hideTaskbarProgress()
                        progress.indeterminate || progress.total <= 0 -> {
                            nucleusWindow.showTaskbarIndeterminate()
                        }
                        else -> {
                            nucleusWindow.showTaskbarProgress(
                                (progress.completed.toDouble() / progress.total.toDouble())
                                    .coerceIn(0.0, 1.0),
                            )
                        }
                    }
                }
            },
            onStageFailed = { message ->
                uiScope.launch {
                    nucleusWindow.showTaskbarError()
                    AppLogger.w(TAG, message)
                }
            },
            onOpenTask = { taskId, target ->
                uiScope.launch {
                    openPlaylistMigrationFromNotification(taskId, target.name)
                    showWindow()
                }
            },
            onTerminalAttention = {
                uiScope.launch { nucleusWindow.requestTaskbarAttention() }
            },
        )
    }

    DisposableEffect(scheduler) {
        runCatching { NotificationManager.initialize() }
            .onFailure { AppLogger.w(TAG, "Desktop notification initialization failed", it) }
        installPlaylistMigrationBackgroundScheduler(scheduler::enqueue)
        onDispose {
            installPlaylistMigrationBackgroundScheduler(null)
            scheduler.close()
            nucleusWindow.hideTaskbarProgress()
        }
    }
}

private class DesktopPlaylistMigrationBackgroundScheduler(
    private val onVisibleProgressChanged: (PlaylistMigrationBackgroundProgress?) -> Unit,
    private val onStageFailed: (String) -> Unit,
    private val onOpenTask: (String, PlaylistMigrationOpenTarget) -> Unit,
    private val onTerminalAttention: () -> Unit,
) {
    private val lock = Any()
    private val running = mutableSetOf<String>()
    private val activeProgress = LinkedHashMap<String, PlaylistMigrationBackgroundProgress>()
    @Volatile private var closed = false

    fun enqueue(request: PlaylistMigrationBackgroundRequest) {
        if (closed) return
        val key = request.key()
        val accepted = synchronized(lock) { running.add(key) }
        if (!accepted) return
        runSlice(request)
    }

    fun close() {
        closed = true
        synchronized(lock) {
            running.clear()
            activeProgress.clear()
        }
    }

    private fun runSlice(request: PlaylistMigrationBackgroundRequest) {
        if (closed) return
        val key = request.key()
        var lastProgress: PlaylistMigrationBackgroundProgress? = null
        runPlaylistMigrationBackgroundSlice(
            taskId = request.taskId,
            stage = request.stage.name,
            maxSteps = MAX_STEPS_PER_SLICE,
            onProgress = { progress ->
                if (closed) return@runPlaylistMigrationBackgroundSlice
                lastProgress = progress
                updateVisibleProgress(key, progress)
            },
            completionHandler = { needsContinuation, error ->
                if (closed) return@runPlaylistMigrationBackgroundSlice
                if (error != null) {
                    finishRunning(key)
                    onStageFailed("歌单迁移后台任务失败：$error")
                    return@runPlaylistMigrationBackgroundSlice
                }
                if (needsContinuation) {
                    runSlice(request)
                    return@runPlaylistMigrationBackgroundSlice
                }

                finishRunning(key)
                lastProgress
                    ?.takeIf { it.terminal && it.stage == request.stage }
                    ?.let(::publishTerminalNotification)
            },
        )
    }

    private fun updateVisibleProgress(
        key: String,
        progress: PlaylistMigrationBackgroundProgress,
    ) {
        val visible = synchronized(lock) {
            if (progress.terminal) activeProgress.remove(key) else activeProgress[key] = progress
            activeProgress.values.lastOrNull()
        }
        onVisibleProgressChanged(visible)
    }

    private fun finishRunning(key: String) {
        val visible = synchronized(lock) {
            running.remove(key)
            activeProgress.remove(key)
            activeProgress.values.lastOrNull()
        }
        onVisibleProgressChanged(visible)
    }

    private fun publishTerminalNotification(progress: PlaylistMigrationBackgroundProgress) {
        onTerminalAttention()
        val target = progress.openTarget ?: when (progress.stage.name) {
            "Writing" -> PlaylistMigrationOpenTarget.Result
            else -> PlaylistMigrationOpenTarget.Review
        }
        runCatching {
            if (!NotificationManager.isAvailable()) return@runCatching
            notification(
                title = progress.title,
                message = progress.detail,
                onActivated = { onOpenTask(progress.taskId, target) },
            ).send()
        }.onFailure { failure ->
            AppLogger.w(TAG, "Desktop migration result notification failed", failure)
        }
    }

    private fun PlaylistMigrationBackgroundRequest.key(): String = "$taskId:${stage.name}"

    private companion object {
        const val MAX_STEPS_PER_SLICE = 24
    }
}

private const val TAG = "PlaylistMigration"
