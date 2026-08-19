package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Coordinates download state and local-media invalidation without owning UI state. */
internal class OfflineLibraryControllerCoordinator(
    private val scope: CoroutineScope,
    private val downloadRepository: DownloadRepository,
    private val localRepository: LocalMusicRepository,
    private val onDownloadStates: (Map<String, DownloadState>) -> Unit,
    private val onDownloadTasks: (List<DownloadTask>) -> Unit,
    private val hasLocalMusicPermission: () -> Boolean,
    private val shouldShowLocalMusicLoading: () -> Boolean,
    private val refreshLocalMusic: suspend (forceRefresh: Boolean, showLoading: Boolean) -> Unit,
) {
    private var started = false
    private var observedCompletedDownloadTaskIds = emptySet<String>()
    private var pendingLocalMusicMediaRefresh: Job? = null

    fun start() {
        if (started) return
        started = true
        scope.launch {
            downloadRepository.states.collect { states ->
                onDownloadStates(states)
            }
        }
        scope.launch {
            downloadRepository.tasks.collect { tasks ->
                onDownloadTasks(tasks)
                val completedIds = tasks
                    .asSequence()
                    .filter { it.status == DownloadTaskStatus.Completed }
                    .map { it.id }
                    .toSet()
                val newlyCompleted = completedIds - observedCompletedDownloadTaskIds
                observedCompletedDownloadTaskIds = completedIds
                if (newlyCompleted.isNotEmpty() && hasLocalMusicPermission()) {
                    refreshLocalMusic(true, shouldShowLocalMusicLoading())
                }
            }
        }
        scope.launch {
            localRepository.mediaChangeEvents.collect {
                pendingLocalMusicMediaRefresh?.cancel()
                pendingLocalMusicMediaRefresh = launch {
                    delay(MEDIA_CHANGE_DEBOUNCE_MS)
                    if (hasLocalMusicPermission() && localRepository.isDatabaseReady()) {
                        refreshLocalMusic(true, shouldShowLocalMusicLoading())
                    }
                }
            }
        }
    }

    private companion object {
        const val MEDIA_CHANGE_DEBOUNCE_MS = 750L
    }
}
