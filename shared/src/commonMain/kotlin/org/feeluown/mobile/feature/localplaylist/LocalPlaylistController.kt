package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LocalPlaylistUiState(
    val playlists: List<LocalPlaylist> = emptyList(),
    val selectedPlaylist: LocalPlaylist? = null,
    val selectedTracks: List<MusicTrack> = emptyList(),
    val selectedError: String? = null,
    val operationError: String? = null,
    val importPreview: LocalPlaylistImportPreview? = null,
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

interface LocalPlaylistUiActions {
    fun refresh()
    fun create(title: String)
    fun open(playlist: LocalPlaylist)
    fun close()
    fun canRemove(track: MusicTrack): Boolean
    fun remove(track: MusicTrack)
    fun canDeleteSelected(): Boolean
    fun deleteSelected()
    fun prepareImport(fileName: String, content: String)
    fun existingForImport(preview: LocalPlaylistImportPreview): LocalPlaylist?
    fun cancelImport()
    fun importPlaylist(mode: LocalPlaylistImportMode, replacePlaylistId: String? = null)
    fun exportSelected(onReady: (LocalPlaylistFile) -> Unit)
}

interface LocalPlaylistFeatureController : LocalPlaylistUiActions {
    val uiState: StateFlow<LocalPlaylistUiState>
}

internal class LocalPlaylistController(
    private val repository: LocalPlaylistRepository,
    private val navigator: AppNavigator,
    private val scope: CoroutineScope,
    private val state: PlaylistControllerState,
    private val toMusicTracks: (LocalPlaylist) -> List<MusicTrack>,
    private val toLocalPlaylistTrack: (MusicTrack) -> LocalPlaylistTrack?,
    private val setLoading: (Boolean) -> Unit,
    private val setMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) : LocalPlaylistFeatureController {
    private val mutableUiState = MutableStateFlow(LocalPlaylistUiState())
    override val uiState: StateFlow<LocalPlaylistUiState> = mutableUiState.asStateFlow()

    private var featureLoading = false
    private var featureMessage: String? = null
    private var featureErrorMessage: String? = null

    init {
        state.observeLocalPlaylistChanges(::publishUiState)
    }

    override fun refresh() {
        scope.launch { refreshInternal(showMessage = true) }
    }

    suspend fun refreshInternal(showMessage: Boolean): List<LocalPlaylist> {
        if (showMessage) publishLoading("正在加载本地歌单")
        var resultPlaylists = state.localPlaylists
        runCatching { repository.list() }
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

    suspend fun loadForContent(): List<LocalPlaylist> {
        val loaded = repository.list()
        applyLoadedPlaylists(loaded)
        return loaded
    }

    fun refreshTrackPresentation() {
        state.selectedLocalPlaylist?.let { selected ->
            state.selectedLocalPlaylistTracks = toMusicTracks(selected)
        }
    }

    fun canAddTrack(track: MusicTrack): Boolean = toLocalPlaylistTrack(track) != null

    fun openTargetPicker(track: MusicTrack) {
        if (!canAddTrack(track)) return
        state.playlistTargetTrack = track
        state.playlistTargetType = PlaylistTargetType.Local
        state.playlistTargetPickerShowSwitcher = false
        state.playlistOperationTargets = emptyList()
        state.playlistOperationError = null
        state.localPlaylistOperationError = if (state.localPlaylists.isEmpty()) "请先新建本地歌单" else null
    }

    fun closeTargetPicker() {
        state.playlistTargetTrack = null
        state.playlistTargetType = PlaylistTargetType.Provider
        state.playlistTargetPickerShowSwitcher = true
        state.playlistOperationTargets = emptyList()
        state.playlistOperationError = null
        state.localPlaylistOperationError = null
    }

    override fun create(title: String) {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank()) return
        scope.launch {
            publishLoading("正在新建本地歌单")
            runCatching { repository.create(normalizedTitle) }
                .onSuccess { result ->
                    if (result.success) {
                        result.playlist?.let { state.localPlaylists = state.localPlaylists + it }
                        state.localPlaylistOperationError = null
                        finishLoading(result.message.ifBlank { "本地歌单已新建" })
                    } else {
                        val message = result.message.ifBlank { "新建本地歌单失败" }
                        state.localPlaylistOperationError = message
                        finishLoading(message)
                    }
                }
                .onFailure(::publishError)
        }
    }

    fun addTargetTrackTo(playlist: LocalPlaylist) {
        scope.launch { addTargetTrackToAwait(playlist) }
    }

    suspend fun addTargetTrackToAwait(playlist: LocalPlaylist): LocalPlaylistOperationResult? {
        val track = state.playlistTargetTrack ?: return null
        val localTrack = toLocalPlaylistTrack(track) ?: return null
        publishLoading("正在添加到本地歌单")
        return runCatching { repository.addTrack(playlist, localTrack) }
            .fold(
                onSuccess = { result ->
                    val resultMessage = result.message.ifBlank {
                        if (result.success) "已添加到：${playlist.title}" else "添加失败"
                    }
                    state.playlistOperationFeedback = resultMessage
                    if (result.success) {
                        state.localPlaylistOperationError = null
                        result.playlist?.let(::replacePlaylist)
                        closeTargetPicker()
                    } else {
                        state.localPlaylistOperationError = resultMessage
                    }
                    finishLoading(resultMessage)
                    result.copy(message = resultMessage)
                },
                onFailure = { throwable ->
                    val message = throwable.message ?: throwable::class.simpleName.orEmpty().ifBlank { "添加失败" }
                    state.localPlaylistOperationError = message
                    publishError(throwable)
                    LocalPlaylistOperationResult(success = false, message = message)
                },
            )
    }

    override fun open(playlist: LocalPlaylist) {
        navigator.navigate(AppRoute.LocalPlaylist)
        state.selectedLocalPlaylist = playlist
        state.selectedLocalPlaylistTracks = toMusicTracks(playlist)
        state.selectedLocalPlaylistError = null
        featureErrorMessage = null
        publishUiState()
    }

    override fun close() {
        navigator.pop(AppRoute.LocalPlaylist)
        state.selectedLocalPlaylist = null
        state.selectedLocalPlaylistTracks = emptyList()
        state.selectedLocalPlaylistError = null
    }

    override fun canRemove(track: MusicTrack): Boolean {
        val playlist = state.selectedLocalPlaylist ?: return false
        val localTrack = toLocalPlaylistTrack(track) ?: return false
        return playlist.tracks.any { it.uri == localTrack.uri }
    }

    override fun remove(track: MusicTrack) {
        val playlist = state.selectedLocalPlaylist ?: return
        val localTrack = toLocalPlaylistTrack(track) ?: return
        if (!canRemove(track)) return
        scope.launch {
            publishLoading("正在从本地歌单移除")
            runCatching { repository.removeTrack(playlist, localTrack.uri) }
                .onSuccess { result ->
                    if (result.success) {
                        val updated = result.playlist ?: playlist.copy(
                            tracks = playlist.tracks.filterNot { it.uri == localTrack.uri },
                        )
                        replacePlaylist(updated)
                        val resultMessage = result.message.ifBlank { "已从本地歌单移除" }
                        state.playlistOperationFeedback = resultMessage
                        state.selectedLocalPlaylistError = null
                        finishLoading(resultMessage)
                    } else {
                        val message = result.message.ifBlank { "移除失败" }
                        state.selectedLocalPlaylistError = message
                        finishLoading(message)
                    }
                }
                .onFailure { throwable ->
                    state.selectedLocalPlaylistError = throwable.message ?: "移除失败"
                    publishError(throwable)
                }
        }
    }

    override fun canDeleteSelected(): Boolean = state.selectedLocalPlaylist != null

    override fun deleteSelected() {
        val playlist = state.selectedLocalPlaylist ?: return
        scope.launch {
            publishLoading("正在删除本地歌单")
            runCatching { repository.delete(playlist) }
                .onSuccess { result ->
                    val message = result.message.ifBlank { if (result.success) "歌单已删除" else "删除歌单失败" }
                    if (result.success) {
                        close()
                        state.localPlaylists = state.localPlaylists.filterNot { it.id == playlist.id }
                    }
                    finishLoading(message)
                }
                .onFailure(::publishError)
        }
    }

    override fun prepareImport(fileName: String, content: String) {
        val preview = LocalPlaylistFileCodec.decode(fileName, content)
        state.localPlaylistImportPreview = preview
        publishMessage(
            if (preview.skippedLineCount > 0) {
                "已读取 ${preview.tracks.size} 首歌曲，跳过 ${preview.skippedLineCount} 行"
            } else {
                "请选择导入方式"
            }
        )
    }

    override fun existingForImport(preview: LocalPlaylistImportPreview): LocalPlaylist? =
        state.localPlaylists.firstOrNull { it.title.trim() == preview.title.trim() }

    override fun cancelImport() {
        state.localPlaylistImportPreview = null
    }

    override fun importPlaylist(mode: LocalPlaylistImportMode, replacePlaylistId: String?) {
        val preview = state.localPlaylistImportPreview ?: return
        val replacePlaylist = replacePlaylistId?.let { id -> state.localPlaylists.firstOrNull { it.id == id } }
        scope.launch {
            publishLoading("正在导入本地歌单")
            runCatching { repository.importPlaylist(preview, mode, replacePlaylist) }
                .onSuccess { result ->
                    val message = result.message.ifBlank { if (result.success) "歌单已导入" else "导入失败" }
                    if (result.success) {
                        state.localPlaylistImportPreview = null
                        refreshInternal(showMessage = false)
                    }
                    finishLoading(message)
                }
                .onFailure(::publishError)
        }
    }

    override fun exportSelected(onReady: (LocalPlaylistFile) -> Unit) {
        val playlist = state.selectedLocalPlaylist ?: return
        scope.launch {
            publishLoading("正在准备导出文件")
            runCatching { repository.export(playlist) }
                .onSuccess { file ->
                    onReady(file)
                    finishLoading("导出文件已准备")
                }
                .onFailure(::publishError)
        }
    }

    private fun applyLoadedPlaylists(loaded: List<LocalPlaylist>) {
        state.localPlaylists = loaded
        state.selectedLocalPlaylist?.let { selected ->
            val updated = loaded.firstOrNull { it.id == selected.id }
            if (updated == null) {
                close()
            } else {
                state.selectedLocalPlaylist = updated
                state.selectedLocalPlaylistTracks = toMusicTracks(updated)
            }
        }
    }

    private fun replacePlaylist(updated: LocalPlaylist) {
        state.localPlaylists = state.localPlaylists.map { if (it.id == updated.id) updated else it }
        if (state.selectedLocalPlaylist?.id == updated.id) {
            state.selectedLocalPlaylist = updated
            state.selectedLocalPlaylistTracks = toMusicTracks(updated)
        }
    }

    private fun publishLoading(message: String) {
        featureLoading = true
        featureMessage = message
        featureErrorMessage = null
        setLoading(true)
        setMessage(message)
        publishUiState()
    }

    private fun finishLoading(message: String) {
        featureLoading = false
        featureMessage = message
        featureErrorMessage = null
        setMessage(message)
        setLoading(false)
        publishUiState()
    }

    private fun publishMessage(message: String) {
        featureMessage = message
        featureErrorMessage = null
        setMessage(message)
        publishUiState()
    }

    private fun publishError(throwable: Throwable) {
        val message = throwable.message ?: throwable::class.simpleName.orEmpty().ifBlank { "操作失败" }
        featureLoading = false
        featureMessage = message
        featureErrorMessage = message
        onError(throwable)
        setLoading(false)
        publishUiState()
    }

    private fun publishUiState() {
        mutableUiState.value = LocalPlaylistUiState(
            playlists = state.localPlaylists,
            selectedPlaylist = state.selectedLocalPlaylist,
            selectedTracks = state.selectedLocalPlaylistTracks,
            selectedError = state.selectedLocalPlaylistError,
            operationError = state.localPlaylistOperationError,
            importPreview = state.localPlaylistImportPreview,
            isLoading = featureLoading,
            message = featureMessage,
            errorMessage = featureErrorMessage,
        )
    }
}
