package org.feeluown.mobile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

sealed class DownloadState {
    data object NotDownloaded : DownloadState()
    data object Queued : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data object Paused : DownloadState()
    data class Downloaded(val uri: String) : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

enum class DownloadTaskStatus {
    Queued,
    Downloading,
    Paused,
    Failed,
    Completed,
}

data class DownloadTask(
    val id: String,
    val track: MusicTrack,
    val status: DownloadTaskStatus,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val downloadedBytes: Long = 0,
    val totalBytes: Long? = null,
    val failureMessage: String? = null,
    val completedUri: String? = null,
)

interface LocalMusicRepository {
    val mediaChangeEvents: Flow<Unit>
        get() = emptyFlow()

    suspend fun updateScanSettings(settings: LocalMusicScanSettings)
    suspend fun isDatabaseReady(): Boolean = false
    suspend fun isDatabaseStale(): Boolean = true
    suspend fun directories(): List<LocalMusicDirectory>
    suspend fun tracks(): List<MusicTrack>
    suspend fun refreshDatabase(): List<MusicTrack>
    suspend fun search(keyword: String): List<MusicTrack>
    suspend fun updateMetadata(track: MusicTrack, metadata: LocalTrackMetadata) = Unit
    suspend fun saveLyrics(track: MusicTrack, lyrics: String) = Unit
}

interface DownloadRepository {
    val states: StateFlow<Map<String, DownloadState>>
    val tasks: StateFlow<List<DownloadTask>>
        get() = EMPTY_DOWNLOAD_TASKS
    suspend fun load()
    suspend fun download(track: MusicTrack)
    suspend fun download(track: MusicTrack, payload: PlaybackPayload) = download(track)
    suspend fun updateParallelism(parallelism: Int) = Unit
    suspend fun pause(taskId: String) = Unit
    suspend fun resume(taskId: String) = Unit
    suspend fun retry(taskId: String) = Unit
    suspend fun deleteTask(taskId: String, deleteFile: Boolean = true) = Unit
    suspend fun deleteDownloaded(track: MusicTrack)
}

private val EMPTY_DOWNLOAD_TASKS = MutableStateFlow<List<DownloadTask>>(emptyList())

data class CacheUsage(
    val audioBytes: Long = 0,
    val imageBytes: Long = 0,
) {
    val totalBytes: Long
        get() = audioBytes + imageBytes
}

data class CacheLimit(
    val audioMaxBytes: Long,
    val imageMaxBytes: Long,
)

interface ResourceCacheRepository {
    val usage: StateFlow<CacheUsage>
    suspend fun refreshUsage()
    suspend fun clearAll()
    suspend fun updateLimit(limit: CacheLimit)
}

object NoOpResourceCacheRepository : ResourceCacheRepository {
    private val mutableUsage = MutableStateFlow(CacheUsage())
    override val usage: StateFlow<CacheUsage> = mutableUsage

    override suspend fun refreshUsage() = Unit

    override suspend fun clearAll() = Unit

    override suspend fun updateLimit(limit: CacheLimit) = Unit
}

interface DebugLogRepository {
    val isAvailable: Boolean
    suspend fun logLines(): List<String>
    suspend fun exportLogFile(lines: List<String>): String = "当前平台不支持导出日志文件"
}

object NoOpDebugLogRepository : DebugLogRepository {
    override val isAvailable: Boolean = false

    override suspend fun logLines(): List<String> = emptyList()
}

enum class DebugLogLevel {
    Debug,
    Info,
    Warning,
    Error,
}
