package org.feeluown.mobile.feature.localplaylist

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LocalPlaylistFeatureState<Track, Playlist, Preview>(
    val playlists: List<Playlist> = emptyList(),
    val selectedPlaylist: Playlist? = null,
    val selectedTracks: List<Track> = emptyList(),
    val selectedError: String? = null,
    val operationError: String? = null,
    val importPreview: Preview? = null,
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

interface LocalPlaylistFeatureOperations<Track, Playlist, Preview, Mode, Export, Result> {
    suspend fun list(): List<Playlist>
    suspend fun create(title: String): Result
    suspend fun delete(playlist: Playlist): Result
    suspend fun addTrack(playlist: Playlist, track: Track): Result
    suspend fun removeTrack(playlist: Playlist, track: Track): Result
    suspend fun importPlaylist(preview: Preview, mode: Mode, replacePlaylist: Playlist?): Result
    suspend fun export(playlist: Playlist): Export

    fun decode(fileName: String, content: String): Preview
    fun tracks(playlist: Playlist): List<Track>
    fun canAddTrack(track: Track): Boolean
    fun containsTrack(playlist: Playlist, track: Track): Boolean
    fun removeTrackLocally(playlist: Playlist, track: Track): Playlist
    fun playlistId(playlist: Playlist): String
    fun playlistTitle(playlist: Playlist): String
    fun previewTitle(preview: Preview): String
    fun previewTrackCount(preview: Preview): Int
    fun previewSkippedLineCount(preview: Preview): Int
    fun resultSuccess(result: Result): Boolean
    fun resultMessage(result: Result): String
    fun resultPlaylist(result: Result): Playlist?
    fun withResultMessage(result: Result, message: String): Result
    fun failureResult(message: String): Result
}

interface LocalPlaylistFeatureOwner<Track, Playlist, Preview, Mode, Export, Result> {
    val uiState: StateFlow<LocalPlaylistFeatureState<Track, Playlist, Preview>>

    fun refresh()
    suspend fun loadForContent(): List<Playlist>
    fun create(title: String)
    fun open(playlist: Playlist)
    fun close()
    fun canRemove(track: Track): Boolean
    fun remove(track: Track)
    fun canDeleteSelected(): Boolean
    fun deleteSelected()
    fun prepareImport(fileName: String, content: String)
    fun existingForImport(preview: Preview): Playlist?
    fun cancelImport()
    fun importPlaylist(mode: Mode, replacePlaylistId: String? = null)
    fun exportSelected(onReady: (Export) -> Unit)

    fun canAddTrack(track: Track): Boolean
    suspend fun addTrack(playlist: Playlist, track: Track): Result

    fun openTargetPicker(track: Track)
    fun closeTargetPicker()
    fun addTargetTrackTo(playlist: Playlist)
}

fun <Track, Playlist, Preview, Mode, Export, Result> createLocalPlaylistFeatureOwner(
    operations: LocalPlaylistFeatureOperations<Track, Playlist, Preview, Mode, Export, Result>,
    scope: CoroutineScope,
    openPlaylist: () -> Unit,
    closePlaylist: () -> Unit,
    onMessage: (String) -> Unit = {},
    onError: (Throwable) -> Unit = {},
): LocalPlaylistFeatureOwner<Track, Playlist, Preview, Mode, Export, Result> =
    DefaultLocalPlaylistFeatureOwner(
        operations = operations,
        scope = scope,
        openPlaylist = openPlaylist,
        closePlaylist = closePlaylist,
        onMessage = onMessage,
        onError = onError,
    )

private class DefaultLocalPlaylistFeatureOwner<Track, Playlist, Preview, Mode, Export, Result>(
    private val operations: LocalPlaylistFeatureOperations<Track, Playlist, Preview, Mode, Export, Result>,
    private val scope: CoroutineScope,
    private val openPlaylist: () -> Unit,
    private val closePlaylist: () -> Unit,
    private val onMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) : LocalPlaylistFeatureOwner<Track, Playlist, Preview, Mode, Export, Result> {
    private val mutableUiState = MutableStateFlow(LocalPlaylistFeatureState<Track, Playlist, Preview>())
    override val uiState: StateFlow<LocalPlaylistFeatureState<Track, Playlist, Preview>> = mutableUiState.asStateFlow()

    private var targetTrack: Track? = null

    override fun refresh() {
        scope.launch { refreshInternal(showMessage = true) }
    }

    override suspend fun loadForContent(): List<Playlist> = refreshInternal(showMessage = false)

    private suspend fun refreshInternal(showMessage: Boolean): List<Playlist> {
        if (showMessage) publishLoading("正在加载本地歌单")
        var resultPlaylists = uiState.value.playlists
        runCatching { operations.list() }
            .onSuccess { loaded ->
                resultPlaylists = loaded
                applyLoadedPlaylists(loaded)
                if (showMessage) {
                    finishLoading(if (loaded.isEmpty()) "暂无本地歌单" else "本地歌单 ${loaded.size} 个")
                }
            }
            .onFailure { throwable -> if (showMessage) publishError(throwable) }
        return resultPlaylists
    }

    override fun create(title: String) {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank()) return
        scope.launch {
            publishLoading("正在新建本地歌单")
            runCatching { operations.create(normalizedTitle) }
                .onSuccess { result ->
                    if (operations.resultSuccess(result)) {
                        operations.resultPlaylist(result)?.let { playlist ->
                            mutableUiState.value = uiState.value.copy(
                                playlists = uiState.value.playlists + playlist,
                                operationError = null,
                            )
                        }
                        finishLoading(operations.resultMessage(result).ifBlank { "本地歌单已新建" })
                    } else {
                        val message = operations.resultMessage(result).ifBlank { "新建本地歌单失败" }
                        mutableUiState.value = uiState.value.copy(operationError = message)
                        finishLoading(message)
                    }
                }
                .onFailure(::publishError)
        }
    }

    override fun open(playlist: Playlist) {
        openPlaylist()
        mutableUiState.value = uiState.value.copy(
            selectedPlaylist = playlist,
            selectedTracks = operations.tracks(playlist),
            selectedError = null,
            errorMessage = null,
        )
    }

    override fun close() {
        closePlaylist()
        mutableUiState.value = uiState.value.copy(
            selectedPlaylist = null,
            selectedTracks = emptyList(),
            selectedError = null,
        )
    }

    override fun canRemove(track: Track): Boolean =
        uiState.value.selectedPlaylist?.let { playlist -> operations.containsTrack(playlist, track) } == true

    override fun remove(track: Track) {
        val playlist = uiState.value.selectedPlaylist ?: return
        if (!canRemove(track)) return
        scope.launch {
            publishLoading("正在从本地歌单移除")
            runCatching { operations.removeTrack(playlist, track) }
                .onSuccess { result ->
                    if (operations.resultSuccess(result)) {
                        val updated = operations.resultPlaylist(result) ?: operations.removeTrackLocally(playlist, track)
                        replacePlaylist(updated)
                        val message = operations.resultMessage(result).ifBlank { "已从本地歌单移除" }
                        mutableUiState.value = uiState.value.copy(selectedError = null)
                        finishLoading(message)
                    } else {
                        val message = operations.resultMessage(result).ifBlank { "移除失败" }
                        mutableUiState.value = uiState.value.copy(selectedError = message)
                        finishLoading(message)
                    }
                }
                .onFailure { throwable ->
                    mutableUiState.value = uiState.value.copy(selectedError = throwable.message ?: "移除失败")
                    publishError(throwable)
                }
        }
    }

    override fun canDeleteSelected(): Boolean = uiState.value.selectedPlaylist != null

    override fun deleteSelected() {
        val playlist = uiState.value.selectedPlaylist ?: return
        scope.launch {
            publishLoading("正在删除本地歌单")
            runCatching { operations.delete(playlist) }
                .onSuccess { result ->
                    val message = operations.resultMessage(result).ifBlank {
                        if (operations.resultSuccess(result)) "歌单已删除" else "删除歌单失败"
                    }
                    if (operations.resultSuccess(result)) {
                        close()
                        mutableUiState.value = uiState.value.copy(
                            playlists = uiState.value.playlists.filterNot {
                                operations.playlistId(it) == operations.playlistId(playlist)
                            },
                        )
                    }
                    finishLoading(message)
                }
                .onFailure(::publishError)
        }
    }

    override fun prepareImport(fileName: String, content: String) {
        val preview = operations.decode(fileName, content)
        mutableUiState.value = uiState.value.copy(importPreview = preview)
        val skipped = operations.previewSkippedLineCount(preview)
        publishMessage(
            if (skipped > 0) {
                "已读取 ${operations.previewTrackCount(preview)} 首歌曲，跳过 $skipped 行"
            } else {
                "请选择导入方式"
            },
        )
    }

    override fun existingForImport(preview: Preview): Playlist? = uiState.value.playlists.firstOrNull {
        operations.playlistTitle(it).trim() == operations.previewTitle(preview).trim()
    }

    override fun cancelImport() {
        mutableUiState.value = uiState.value.copy(importPreview = null)
    }

    override fun importPlaylist(mode: Mode, replacePlaylistId: String?) {
        val preview = uiState.value.importPreview ?: return
        val replacePlaylist = replacePlaylistId?.let { id ->
            uiState.value.playlists.firstOrNull { operations.playlistId(it) == id }
        }
        scope.launch {
            publishLoading("正在导入本地歌单")
            runCatching { operations.importPlaylist(preview, mode, replacePlaylist) }
                .onSuccess { result ->
                    val message = operations.resultMessage(result).ifBlank {
                        if (operations.resultSuccess(result)) "歌单已导入" else "导入失败"
                    }
                    if (operations.resultSuccess(result)) {
                        mutableUiState.value = uiState.value.copy(importPreview = null)
                        refreshInternal(showMessage = false)
                    }
                    finishLoading(message)
                }
                .onFailure(::publishError)
        }
    }

    override fun exportSelected(onReady: (Export) -> Unit) {
        val playlist = uiState.value.selectedPlaylist ?: return
        scope.launch {
            publishLoading("正在准备导出文件")
            runCatching { operations.export(playlist) }
                .onSuccess { file ->
                    onReady(file)
                    finishLoading("导出文件已准备")
                }
                .onFailure(::publishError)
        }
    }

    override fun canAddTrack(track: Track): Boolean = operations.canAddTrack(track)

    override suspend fun addTrack(playlist: Playlist, track: Track): Result {
        if (!operations.canAddTrack(track)) return operations.failureResult("当前歌曲无法添加到本地歌单")
        targetTrack = track
        publishLoading("正在添加到本地歌单")
        return runCatching { operations.addTrack(playlist, track) }
            .fold(
                onSuccess = { result ->
                    val message = operations.resultMessage(result).ifBlank {
                        if (operations.resultSuccess(result)) "已添加到：${operations.playlistTitle(playlist)}" else "添加失败"
                    }
                    if (operations.resultSuccess(result)) {
                        operations.resultPlaylist(result)?.let(::replacePlaylist)
                        targetTrack = null
                        mutableUiState.value = uiState.value.copy(operationError = null)
                    } else {
                        mutableUiState.value = uiState.value.copy(operationError = message)
                    }
                    finishLoading(message)
                    operations.withResultMessage(result, message)
                },
                onFailure = { throwable ->
                    val message = throwable.message ?: throwable::class.simpleName.orEmpty().ifBlank { "添加失败" }
                    mutableUiState.value = uiState.value.copy(operationError = message)
                    publishError(throwable)
                    operations.failureResult(message)
                },
            )
    }

    override fun openTargetPicker(track: Track) {
        if (canAddTrack(track)) targetTrack = track
    }

    override fun closeTargetPicker() {
        targetTrack = null
    }

    override fun addTargetTrackTo(playlist: Playlist) {
        val track = targetTrack ?: return
        scope.launch { addTrack(playlist, track) }
    }

    private fun applyLoadedPlaylists(loaded: List<Playlist>) {
        val selected = uiState.value.selectedPlaylist
        if (selected == null) {
            mutableUiState.value = uiState.value.copy(playlists = loaded)
            return
        }
        val updated = loaded.firstOrNull { operations.playlistId(it) == operations.playlistId(selected) }
        if (updated == null) {
            close()
            mutableUiState.value = uiState.value.copy(playlists = loaded)
        } else {
            mutableUiState.value = uiState.value.copy(
                playlists = loaded,
                selectedPlaylist = updated,
                selectedTracks = operations.tracks(updated),
            )
        }
    }

    private fun replacePlaylist(updated: Playlist) {
        val id = operations.playlistId(updated)
        val selected = uiState.value.selectedPlaylist
        mutableUiState.value = uiState.value.copy(
            playlists = uiState.value.playlists.map { if (operations.playlistId(it) == id) updated else it },
            selectedPlaylist = if (selected != null && operations.playlistId(selected) == id) updated else selected,
            selectedTracks = if (selected != null && operations.playlistId(selected) == id) {
                operations.tracks(updated)
            } else {
                uiState.value.selectedTracks
            },
        )
    }

    private fun publishLoading(message: String) {
        onMessage(message)
        mutableUiState.value = uiState.value.copy(
            isLoading = true,
            message = message,
            errorMessage = null,
        )
    }

    private fun finishLoading(message: String) {
        onMessage(message)
        mutableUiState.value = uiState.value.copy(
            isLoading = false,
            message = message,
            errorMessage = null,
        )
    }

    private fun publishMessage(message: String) {
        onMessage(message)
        mutableUiState.value = uiState.value.copy(message = message, errorMessage = null)
    }

    private fun publishError(throwable: Throwable) {
        val message = throwable.message ?: throwable::class.simpleName.orEmpty().ifBlank { "操作失败" }
        onError(throwable)
        mutableUiState.value = uiState.value.copy(
            isLoading = false,
            message = message,
            errorMessage = message,
        )
    }
}
