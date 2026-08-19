package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class DownloadController(
    private val providerRepository: ProviderMusicRepository,
    private val downloadRepository: DownloadRepository,
    private val localRepository: LocalMusicRepository,
    private val localMusicController: LocalMusicController,
    private val scope: CoroutineScope,
    val state: DownloadControllerState = DownloadControllerState(),
    private val unavailablePlaybackPolicy: () -> UnavailablePlaybackPolicy,
    private val smartReplacementProviderIds: () -> Set<String>,
    private val smartReplacementMinScore: () -> Double,
    private val isLocalMusicSectionActive: () -> Boolean,
    private val persistSettings: () -> Unit,
    private val setMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val offlineLibraryCoordinator = OfflineLibraryControllerCoordinator(
        scope = scope,
        downloadRepository = downloadRepository,
        localRepository = localRepository,
        onDownloadStates = { state.states = it },
        onDownloadTasks = { state.tasks = it },
        hasLocalMusicPermission = { localMusicController.hasPermission },
        shouldShowLocalMusicLoading = isLocalMusicSectionActive,
        refreshLocalMusic = localMusicController::refresh,
    )

    fun start() {
        offlineLibraryCoordinator.start()
    }

    fun dismissQueueFeedback(feedback: String) {
        if (state.queueFeedback == feedback) state.queueFeedback = null
    }

    fun onParallelismChange(value: Int) {
        state.parallelism = value.coerceIn(1, 5)
        persistSettings()
        scope.launch { downloadRepository.updateParallelism(state.parallelism) }
    }

    fun download(track: MusicTrack) {
        if (track.sourceType != TrackSourceType.Provider) return
        state.queueFeedback = "已加入下载队列：${track.title}"
        scope.launch {
            runCatching {
                val payload = providerRepository.resolve(
                    track,
                    unavailablePlaybackPolicy(),
                    smartReplacementProviderIds(),
                    smartReplacementMinScore(),
                    true,
                    true,
                )
                downloadRepository.download(track, payload)
            }.onFailure(onError)
        }
    }

    fun pause(taskId: String) = runAction { downloadRepository.pause(taskId) }

    fun resume(taskId: String) = runAction { downloadRepository.resume(taskId) }

    fun retry(taskId: String) = runAction { downloadRepository.retry(taskId) }

    fun deleteTask(taskId: String, deleteFile: Boolean) = runAction {
        downloadRepository.deleteTask(taskId, deleteFile)
    }

    fun deleteDownloaded(track: MusicTrack) {
        scope.launch {
            runCatching { downloadRepository.deleteDownloaded(track) }
                .onSuccess {
                    if (localMusicController.hasPermission) {
                        localMusicController.refresh(
                            forceRefresh = true,
                            showLoading = isLocalMusicSectionActive(),
                        )
                    }
                    setMessage("已删除下载：${track.title}")
                }
                .onFailure(onError)
        }
    }

    private fun runAction(action: suspend () -> Unit) {
        scope.launch {
            runCatching { action() }
                .onFailure(onError)
        }
    }
}
