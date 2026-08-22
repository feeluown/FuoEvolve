package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DownloadManagerUiState(
    val tasks: List<DownloadTask> = emptyList(),
    val queueFeedback: String? = null,
)

/** Composition-root binding from application repositories/models to the physical download feature. */
fun createDownloadActionPort(
    providerRepository: ProviderMusicRepository,
    downloadRepository: DownloadRepository,
    localRepository: LocalMusicRepository,
    localMusicController: LocalMusicFeatureController,
    settingsRepository: AppSettingsRepository,
    scope: CoroutineScope,
    isLocalMusicSectionActive: () -> Boolean,
    onFeedback: (String) -> Unit = {},
): DownloadActionPort {
    val repositoryPort = object : DownloadFeatureRepository<MusicTrack, PlaybackPayload, DownloadTask, DownloadState> {
        override val states = downloadRepository.states
        override val tasks = downloadRepository.tasks
        override suspend fun load() = downloadRepository.load()
        override suspend fun download(track: MusicTrack, payload: PlaybackPayload) = downloadRepository.download(track, payload)
        override suspend fun updateParallelism(value: Int) = downloadRepository.updateParallelism(value)
        override suspend fun pause(taskId: String) = downloadRepository.pause(taskId)
        override suspend fun resume(taskId: String) = downloadRepository.resume(taskId)
        override suspend fun retry(taskId: String) = downloadRepository.retry(taskId)
        override suspend fun deleteTask(taskId: String, deleteFile: Boolean) = downloadRepository.deleteTask(taskId, deleteFile)
        override suspend fun deleteDownloaded(track: MusicTrack) = downloadRepository.deleteDownloaded(track)
    }

    val resolver = object : DownloadMediaResolver<MusicTrack, PlaybackPayload> {
        override suspend fun resolve(track: MusicTrack): PlaybackPayload {
            val settings = settingsRepository.state.value.settings
            return providerRepository.resolve(
                track,
                settings.unavailablePlaybackPolicy,
                settings.effectiveReplacementProviderIds(),
                settings.smartReplacementMinScore.coerceIn(0.0, 1.0),
                true,
                true,
            )
        }
    }

    val localLibrary = object : DownloadLocalLibraryPort {
        override val mediaChangeEvents = localRepository.mediaChangeEvents
        override val hasPermission: Boolean get() = localMusicController.hasPermission
        override suspend fun isDatabaseReady(): Boolean = localRepository.isDatabaseReady()
        override fun refresh(forceRefresh: Boolean, showLoading: Boolean) {
            localMusicController.refresh(forceRefresh, showLoading)
        }
    }

    val operations = object : DownloadFeatureOperations<MusicTrack, DownloadTask> {
        override fun isProviderTrack(track: MusicTrack): Boolean = track.sourceType == TrackSourceType.Provider
        override fun trackTitle(track: MusicTrack): String = track.title
        override fun taskId(task: DownloadTask): String = task.id
        override fun isCompleted(task: DownloadTask): Boolean = task.status == DownloadTaskStatus.Completed
    }

    val owner = createDownloadFeatureOwner(
        repository = repositoryPort,
        resolver = resolver,
        localLibrary = localLibrary,
        operations = operations,
        scope = scope,
        shouldShowLocalMusicLoading = isLocalMusicSectionActive,
        persistParallelism = { parallelism ->
            scope.launch {
                settingsRepository.update { current -> current.copy(downloadParallelism = parallelism.coerceIn(1, 5)) }
            }
        },
        onFeedback = onFeedback,
        failureMessage = { throwable ->
            throwable.providerFailureOrNull()?.userMessage
                ?: throwable.message
                ?: throwable::class.simpleName.orEmpty().ifBlank { "下载操作失败" }
        },
    )

    scope.launch {
        val settings = settingsRepository.awaitSettings()
        val parallelism = settings.downloadParallelism.coerceIn(1, 5)
        owner.restoreParallelism(parallelism)
        downloadRepository.updateParallelism(parallelism)
        runCatching { downloadRepository.load() }
        owner.start()
    }

    return BoundDownloadActionPort(owner, scope)
}

internal class ObservableDownloadStates<T>(initialValue: Map<String, T>) {
    private var snapshotValue by mutableStateOf(initialValue)

    val value: Map<String, T>
        get() = snapshotValue

    fun update(value: Map<String, T>) {
        snapshotValue = value
    }
}

private class BoundDownloadActionPort(
    private val owner: DownloadFeatureOwner<MusicTrack, DownloadTask, DownloadState>,
    scope: CoroutineScope,
) : DownloadActionPort {
    private val observableDownloadStates = ObservableDownloadStates(owner.state.value.states)

    override val downloadStates: Map<String, DownloadState>
        get() = observableDownloadStates.value

    override val managerState: StateFlow<DownloadManagerUiState> = owner.state
        .map { state -> DownloadManagerUiState(tasks = state.tasks, queueFeedback = state.queueFeedback) }
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = DownloadManagerUiState(),
        )

    init {
        scope.launch {
            owner.state
                .map { it.states }
                .distinctUntilChanged()
                .collect(observableDownloadStates::update)
        }
    }

    override fun download(track: MusicTrack) = owner.download(track)
    override fun deleteDownload(track: MusicTrack) = owner.deleteDownload(track)
    override fun pause(taskId: String) = owner.pause(taskId)
    override fun resume(taskId: String) = owner.resume(taskId)
    override fun retry(taskId: String) = owner.retry(taskId)
    override fun deleteTask(taskId: String, deleteFile: Boolean) = owner.deleteTask(taskId, deleteFile)
    override fun dismissQueueFeedback(feedback: String) = owner.dismissQueueFeedback(feedback)
}

private fun AppSettings.effectiveReplacementProviderIds(): Set<String> {
    val enabled = enabledProviderIds.ifEmpty { DEFAULT_ENABLED_PROVIDER_IDS }
    return smartReplacementProviderIds.intersect(enabled).ifEmpty { enabled }
}
