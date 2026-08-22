package org.feeluown.mobile

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadFeatureTest {
    @Test
    fun completedTaskRefreshesLocalLibraryAndDownloadUsesResolver() = runTest {
        val repository = FakeRepository()
        val localLibrary = FakeLocalLibrary()
        val owner = createDownloadFeatureOwner(
            repository = repository,
            resolver = object : DownloadMediaResolver<Track, String> {
                override suspend fun resolve(track: Track): String = "payload:${track.id}"
            },
            localLibrary = localLibrary,
            operations = Operations,
            scope = backgroundScope,
            shouldShowLocalMusicLoading = { true },
            persistParallelism = {},
        )

        owner.start()
        runCurrent()

        owner.download(Track("1", "Song", provider = true))
        runCurrent()
        assertEquals("payload:1", repository.lastPayload)

        repository.tasks.value = listOf(Task("task", completed = true))
        runCurrent()
        assertEquals(1, localLibrary.refreshCount)
        assertEquals("已加入下载队列：Song", owner.state.value.queueFeedback)
    }

    private data class Track(val id: String, val title: String, val provider: Boolean)
    private data class Task(val id: String, val completed: Boolean)

    private class FakeRepository : DownloadFeatureRepository<Track, String, Task, String> {
        override val states = MutableStateFlow<Map<String, String>>(emptyMap())
        override val tasks = MutableStateFlow<List<Task>>(emptyList())
        var lastPayload: String? = null
        override suspend fun load() = Unit
        override suspend fun download(track: Track, payload: String) { lastPayload = payload }
        override suspend fun updateParallelism(value: Int) = Unit
        override suspend fun pause(taskId: String) = Unit
        override suspend fun resume(taskId: String) = Unit
        override suspend fun retry(taskId: String) = Unit
        override suspend fun deleteTask(taskId: String, deleteFile: Boolean) = Unit
        override suspend fun deleteDownloaded(track: Track) = Unit
    }

    private class FakeLocalLibrary : DownloadLocalLibraryPort {
        override val mediaChangeEvents = MutableSharedFlow<Unit>()
        override val hasPermission = true
        var refreshCount = 0
        override suspend fun isDatabaseReady() = true
        override fun refresh(forceRefresh: Boolean, showLoading: Boolean) { refreshCount += 1 }
    }

    private object Operations : DownloadFeatureOperations<Track, Task> {
        override fun isProviderTrack(track: Track) = track.provider
        override fun trackTitle(track: Track) = track.title
        override fun taskId(task: Task) = task.id
        override fun isCompleted(task: Task) = task.completed
    }
}
