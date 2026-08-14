package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class AndroidOfflineAwareDownloadRepository(
    private val delegate: DownloadRepository,
    private val assetStore: AndroidOfflineAssetStore,
    scope: CoroutineScope,
) : DownloadRepository {
    private val mutableTasks = MutableStateFlow(delegate.tasks.value)

    override val states: StateFlow<Map<String, DownloadState>> = delegate.states
    override val tasks: StateFlow<List<DownloadTask>> = mutableTasks.asStateFlow()

    init {
        scope.launch {
            delegate.tasks.collect { tasks ->
                syncCompletedAssets(tasks)
                mutableTasks.value = tasks
            }
        }
    }

    override suspend fun load() {
        delegate.load()
        val loadedTasks = delegate.tasks.value
        syncCompletedAssets(loadedTasks)
        mutableTasks.value = loadedTasks
    }

    override suspend fun download(track: MusicTrack) = delegate.download(track)

    override suspend fun download(track: MusicTrack, payload: PlaybackPayload) =
        delegate.download(track, payload)

    override suspend fun updateParallelism(parallelism: Int) =
        delegate.updateParallelism(parallelism)

    override suspend fun pause(taskId: String) = delegate.pause(taskId)

    override suspend fun resume(taskId: String) = delegate.resume(taskId)

    override suspend fun retry(taskId: String) = delegate.retry(taskId)

    override suspend fun deleteTask(taskId: String, deleteFile: Boolean) {
        val completedUri = delegate.tasks.value.firstOrNull { it.id == taskId }?.completedUri
        delegate.deleteTask(taskId, deleteFile)
        if (deleteFile && completedUri != null) {
            assetStore.removeByLocalUri(completedUri)
        }
    }

    override suspend fun deleteDownloaded(track: MusicTrack) {
        val asset = assetStore.findByTrack(track)
        delegate.deleteDownloaded(track)
        asset?.let { assetStore.remove(it.id) }
        track.localUri?.let(assetStore::removeByLocalUri)
    }

    private fun syncCompletedAssets(tasks: List<DownloadTask>) {
        tasks.asSequence()
            .filter { it.status == DownloadTaskStatus.Completed }
            .forEach { task ->
                val localUri = task.completedUri?.takeIf { it.isNotBlank() } ?: return@forEach
                val asset = task.toOfflineAsset(localUri)
                if (assetStore.findById(asset.id) != asset) {
                    assetStore.upsert(asset)
                }
            }
    }

    private fun DownloadTask.toOfflineAsset(localUri: String): OfflineAsset = OfflineAsset(
        id = offlineAssetId(track),
        providerTrackId = track.id,
        providerId = track.providerId,
        providerName = track.providerName,
        source = track.source,
        title = track.title,
        artists = track.artists,
        album = track.album,
        localUri = localUri,
        coverUrl = track.coverUrl,
        durationMs = track.durationMs,
        fileSize = totalBytes ?: downloadedBytes,
        createdAt = updatedAt,
    )
}
