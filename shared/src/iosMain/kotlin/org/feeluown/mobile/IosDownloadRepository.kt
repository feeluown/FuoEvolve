package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSUserDefaults
import kotlin.coroutines.resume

class IosDownloadRepository(
    private val providerRepository: ProviderMusicRepository,
    private val output: IosDownloadOutput,
) : DownloadRepository {
    private val mutableStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    private val mutableTasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    private val defaults = NSUserDefaults.standardUserDefaults
    private val taskRecords = linkedMapOf<String, DownloadTask>()
    private val taskPayloads = mutableMapOf<String, PlaybackPayload>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val activeTaskIds = mutableSetOf<String>()
    private var parallelism = DEFAULT_DOWNLOAD_PARALLELISM

    override val states: StateFlow<Map<String, DownloadState>> = mutableStates.asStateFlow()
    override val tasks: StateFlow<List<DownloadTask>> = mutableTasks.asStateFlow()

    override suspend fun load() {
        taskRecords.clear()
        readTasks().forEach { task ->
            taskRecords[task.id] = if (task.status == DownloadTaskStatus.Downloading) {
                task.copy(status = DownloadTaskStatus.Paused)
            } else task
        }
        readRecords().forEach { (id, uri) ->
            if (id in taskRecords) return@forEach
            taskRecords[id] = DownloadTask(
                id = id,
                track = MusicTrack(id, id, "", "", "", TrackSourceType.Provider),
                status = DownloadTaskStatus.Completed,
                createdAt = 0,
                completedUri = uri,
            )
        }
        logicalClock = taskRecords.values.maxOfOrNull { it.createdAt } ?: 0L
        writeTasks()
        publish()
    }

    override suspend fun download(track: MusicTrack) {
        if (track.sourceType != TrackSourceType.Provider) return
        enqueue(track, null)
    }

    override suspend fun download(track: MusicTrack, payload: PlaybackPayload) {
        if (track.sourceType != TrackSourceType.Provider) return
        enqueue(track, payload)
    }

    private fun enqueue(track: MusicTrack, payload: PlaybackPayload?) {
        val existing = taskRecords[track.id]
        if (existing?.status == DownloadTaskStatus.Downloading || existing?.status == DownloadTaskStatus.Queued) return
        updateTask(
            (existing ?: DownloadTask(track.id, track, DownloadTaskStatus.Queued, nowMillis()))
                .copy(status = DownloadTaskStatus.Queued, failureMessage = null, updatedAt = nowMillis()),
        )
        payload?.let { taskPayloads[track.id] = it }
        schedule()
    }

    override suspend fun updateParallelism(parallelism: Int) {
        this.parallelism = parallelism.coerceIn(1, 5)
        schedule()
    }

    private fun schedule() {
        val ids = taskRecords.values
            .filter { it.status == DownloadTaskStatus.Queued && it.id !in activeTaskIds }
            .sortedBy { it.createdAt }
            .take((parallelism - activeTaskIds.size).coerceAtLeast(0))
            .map { it.id }
        ids.forEach { id ->
            activeTaskIds += id
            scope.launch { runTask(id) }
        }
    }

    private suspend fun runTask(taskId: String) {
        val task = taskRecords[taskId] ?: return
        updateTask(task.copy(status = DownloadTaskStatus.Downloading, failureMessage = null))
        val payload = taskPayloads.remove(taskId) ?: runCatching { providerRepository.resolve(task.track) }.getOrElse { throwable ->
            updateTask(taskRecords.getValue(taskId).copy(status = DownloadTaskStatus.Failed, failureMessage = throwable.message ?: "下载解析失败"))
            activeTaskIds -= taskId
            schedule()
            return
        }
        val result = suspendCancellableCoroutine<Result<String>> { continuation ->
            output.download(
                taskId = taskId,
                url = payload.url,
                headers = payload.headers,
                fileName = downloadFileName(task.track, payload),
                lyrics = payload.lyrics,
            ) { uri, error ->
                if (!continuation.isActive) return@download
                if (uri != null) continuation.resume(Result.success(uri))
                else continuation.resume(Result.failure(IllegalStateException(error ?: "下载失败")))
            }
        }
        result.onSuccess { uri ->
            val records = readRecords() + (taskId to uri)
            writeRecords(records)
            updateTask(taskRecords.getValue(taskId).copy(status = DownloadTaskStatus.Completed, completedUri = uri, failureMessage = null))
        }.onFailure { throwable ->
            taskRecords[taskId]?.takeIf { it.status != DownloadTaskStatus.Paused }?.let {
                updateTask(it.copy(status = DownloadTaskStatus.Failed, failureMessage = throwable.message ?: "下载失败"))
            }
        }
        activeTaskIds -= taskId
        schedule()
    }

    override suspend fun pause(taskId: String) {
        val task = taskRecords[taskId] ?: return
        if (task.status !in setOf(DownloadTaskStatus.Downloading, DownloadTaskStatus.Queued)) return
        output.pause(taskId)
        activeTaskIds -= taskId
        updateTask(task.copy(status = DownloadTaskStatus.Paused))
        schedule()
    }

    override suspend fun resume(taskId: String) {
        taskRecords[taskId]?.takeIf { it.status != DownloadTaskStatus.Completed }?.let { download(it.track) }
    }

    override suspend fun retry(taskId: String) = resume(taskId)

    override suspend fun deleteTask(taskId: String, deleteFile: Boolean) {
        val task = taskRecords.remove(taskId) ?: return
        activeTaskIds -= taskId
        output.deleteTemporary(taskId)
        if (deleteFile) task.completedUri?.let(output::delete)
        val records = readRecords().toMutableMap()
        records.remove(taskId)
        writeRecords(records)
        writeTasks()
        publish()
    }

    override suspend fun deleteDownloaded(track: MusicTrack) {
        val key = track.providerId ?: track.id
        val records = readRecords().toMutableMap()
        val uri = records.remove(key) ?: track.localUri
        if (uri != null) output.delete(uri)
        writeRecords(records)
        taskRecords.remove(key)
        writeTasks()
        publish()
    }

    private fun downloadFileName(track: MusicTrack, payload: PlaybackPayload): String {
        val extension = payload.url.substringBefore('?').substringAfterLast('.', "mp3")
            .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) }
            ?: "mp3"
        val raw = "${payload.title.ifBlank { track.title }} - ${payload.artists.ifBlank { track.artists }}"
        val safe = raw.map { character ->
            if (character in "\\/:*?\"<>|") '_' else character
        }.joinToString("").take(120).ifBlank { "FeelUOwn" }
        return "$safe.$extension"
    }

    private fun readRecords(): Map<String, String> {
        return defaults.stringForKey(KEY_DOWNLOAD_RECORDS)
            ?.lineSequence()
            ?.mapNotNull { line ->
                val values = line.split(RECORD_SEPARATOR, limit = 2)
                if (values.size == 2) values[0] to values[1] else null
            }
            ?.toMap()
            .orEmpty()
    }

    private fun writeRecords(records: Map<String, String>) {
        defaults.setObject(
            records.entries.joinToString("\n") { "${it.key}$RECORD_SEPARATOR${it.value}" },
            KEY_DOWNLOAD_RECORDS,
        )
        defaults.synchronize()
    }

    private fun updateTask(task: DownloadTask) {
        taskRecords[task.id] = task
        writeTasks()
        publish()
    }

    private fun readTasks(): List<DownloadTask> {
        return defaults.stringForKey(KEY_DOWNLOAD_TASKS)
            ?.lineSequence()
            ?.mapNotNull { line ->
                val values = line.split(TASK_FIELD_SEPARATOR)
                if (values.size < 8) return@mapNotNull null
                val status = runCatching { DownloadTaskStatus.valueOf(values[5]) }.getOrNull() ?: return@mapNotNull null
                DownloadTask(
                    id = values[0],
                    track = MusicTrack(
                        id = values[1], title = values[2], artists = values[3], album = values[4],
                        source = values[6], sourceType = TrackSourceType.Provider,
                    ),
                    status = status,
                    createdAt = values[7].toLongOrNull() ?: 0,
                    completedUri = values.getOrNull(8)?.takeIf { it.isNotBlank() },
                    failureMessage = values.getOrNull(9)?.takeIf { it.isNotBlank() },
                )
            }
            ?.toList()
            .orEmpty()
    }

    private fun writeTasks() {
        defaults.setObject(
            taskRecords.values.joinToString("\n") { task ->
                listOf(
                    task.id, task.track.id, task.track.title, task.track.artists, task.track.album,
                    task.status.name, task.track.source, task.createdAt.toString(), task.completedUri.orEmpty(), task.failureMessage.orEmpty(),
                ).joinToString(TASK_FIELD_SEPARATOR)
            },
            KEY_DOWNLOAD_TASKS,
        )
        defaults.synchronize()
    }

    private fun publish() {
        mutableTasks.value = taskRecords.values.sortedWith(
            compareBy<DownloadTask> { if (it.status == DownloadTaskStatus.Downloading) 0 else 1 }.thenByDescending { it.createdAt },
        )
        mutableStates.value = taskRecords.values.associate { task ->
            task.id to when (task.status) {
                DownloadTaskStatus.Queued -> DownloadState.Queued
                DownloadTaskStatus.Downloading -> DownloadState.Downloading(0f)
                DownloadTaskStatus.Paused -> DownloadState.Paused
                DownloadTaskStatus.Failed -> DownloadState.Failed(task.failureMessage ?: "下载失败")
                DownloadTaskStatus.Completed -> DownloadState.Downloaded(task.completedUri.orEmpty())
            }
        }
    }

    private var logicalClock = 0L

    private fun nowMillis(): Long {
        logicalClock += 1
        return logicalClock
    }

    private companion object {
        private const val KEY_DOWNLOAD_RECORDS = "ios_download_records"
        private const val KEY_DOWNLOAD_TASKS = "ios_download_tasks"
        private const val RECORD_SEPARATOR = "\u001f"
        private const val TASK_FIELD_SEPARATOR = "\u001e"
    }
}
