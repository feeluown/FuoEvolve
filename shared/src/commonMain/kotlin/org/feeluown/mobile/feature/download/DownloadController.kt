package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DownloadManagerUiState(
    val tasks: List<DownloadTask> = emptyList(),
    val queueFeedback: String? = null,
)

internal class DownloadController(
    private val providerRepository: ProviderMusicRepository,
    private val downloadRepository: DownloadRepository,
    private val localRepository: LocalMusicRepository,
    private val localMusicController: LocalMusicFeatureController,
    private val scope: CoroutineScope,
    val state: DownloadControllerState = DownloadControllerState(),
    private val unavailablePlaybackPolicy: () -> UnavailablePlaybackPolicy,
    private val smartReplacementProviderIds: () -> Set<String>,
    private val smartReplacementMinScore: () -> Double,
    private val isLocalMusicSectionActive: () -> Boolean,
    private val persistSettings: () -> Unit,
    private val setMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) : DownloadActionPort {
    private val mutableManagerState = MutableStateFlow(DownloadManagerUiState())
    override val managerState: StateFlow<DownloadManagerUiState> = mutableManagerState.asStateFlow()

    private val offlineLibraryCoordinator = OfflineLibraryControllerCoordinator(
        scope = scope,
        downloadRepository = downloadRepository,
        localRepository = localRepository,
        onDownloadStates = { states -> state.states = states },
        onDownloadTasks = ::updateTasks,
        hasLocalMusicPermission = { localMusicController.hasPermission },
        shouldShowLocalMusicLoading = isLocalMusicSectionActive,
        refreshLocalMusic = localMusicController::refresh,
    )

    override val downloadStates: Map<String, DownloadState>
        get() = state.states

    fun start() {
        offlineLibraryCoordinator.start()
    }

    override fun dismissQueueFeedback(feedback: String) {
        if (state.queueFeedback == feedback) {
            updateQueueFeedback(null)
        }
    }

    fun onParallelismChange(value: Int) {
        state.parallelism = value.coerceIn(1, 5)
        persistSettings()
        scope.launch { downloadRepository.updateParallelism(state.parallelism) }
    }

    override fun download(track: MusicTrack) {
        if (track.sourceType != TrackSourceType.Provider) return
        publishFeedback("已加入下载队列：${track.title}")
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
            }.onFailure(::publishError)
        }
    }

    override fun pause(taskId: String) = runAction { downloadRepository.pause(taskId) }

    override fun resume(taskId: String) = runAction { downloadRepository.resume(taskId) }

    override fun retry(taskId: String) = runAction { downloadRepository.retry(taskId) }

    override fun deleteTask(taskId: String, deleteFile: Boolean) = runAction {
        downloadRepository.deleteTask(taskId, deleteFile)
    }

    override fun deleteDownload(track: MusicTrack) {
        scope.launch {
            runCatching { downloadRepository.deleteDownloaded(track) }
                .onSuccess {
                    if (localMusicController.hasPermission) {
                        localMusicController.refresh(
                            forceRefresh = true,
                            showLoading = isLocalMusicSectionActive(),
                        )
                    }
                    publishFeedback("已删除下载：${track.title}")
                }
                .onFailure(::publishError)
        }
    }

    fun deleteDownloaded(track: MusicTrack) = deleteDownload(track)

    private fun updateTasks(tasks: List<DownloadTask>) {
        state.tasks = tasks
        mutableManagerState.value = mutableManagerState.value.copy(tasks = tasks)
    }

    private fun updateQueueFeedback(feedback: String?) {
        state.queueFeedback = feedback
        mutableManagerState.value = mutableManagerState.value.copy(queueFeedback = feedback)
    }

    private fun publishFeedback(message: String) {
        updateQueueFeedback(message)
        setMessage(message)
    }

    private fun publishError(throwable: Throwable) {
        val message = throwable.providerFailureOrNull()?.userMessage
            ?: throwable.message
            ?: throwable::class.simpleName.orEmpty().ifBlank { "下载操作失败" }
        updateQueueFeedback(message)
        onError(throwable)
    }

    private fun runAction(action: suspend () -> Unit) {
        scope.launch {
            runCatching { action() }
                .onFailure(::publishError)
        }
    }
}
