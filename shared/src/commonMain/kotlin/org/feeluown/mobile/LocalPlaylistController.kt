package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
) {
    fun refresh() {
        scope.launch { refreshInternal(showMessage = true) }
    }

    suspend fun refreshInternal(showMessage: Boolean): List<LocalPlaylist> {
        if (showMessage) {
            setLoading(true)
            setMessage("正在加载本地歌单")
        }
        var resultPlaylists = state.localPlaylists
        runCatching { repository.list() }
            .onSuccess { loaded ->
                resultPlaylists = loaded
                applyLoadedPlaylists(loaded)
                if (showMessage) {
                    setMessage(if (loaded.isEmpty()) "暂无本地歌单" else "本地歌单 ${loaded.size} 个")
                }
            }
            .onFailure { if (showMessage) onError(it) }
        if (showMessage) setLoading(false)
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

    fun create(title: String) {
        val normalizedTitle = title.trim()
        if (normalizedTitle.isBlank()) return
        scope.launch {
            setLoading(true)
            setMessage("正在新建本地歌单")
            runCatching { repository.create(normalizedTitle) }
                .onSuccess { result ->
                    if (result.success) {
                        result.playlist?.let { state.localPlaylists = state.localPlaylists + it }
                        state.localPlaylistOperationError = null
                        setMessage(result.message)
                    } else {
                        setMessage(result.message.ifBlank { "新建本地歌单失败" })
                    }
                }
                .onFailure(onError)
            setLoading(false)
        }
    }

    fun addTargetTrackTo(playlist: LocalPlaylist) {
        val track = state.playlistTargetTrack ?: return
        val localTrack = toLocalPlaylistTrack(track) ?: return
        scope.launch {
            setLoading(true)
            setMessage("正在添加到本地歌单")
            runCatching { repository.addTrack(playlist, localTrack) }
                .onSuccess { result ->
                    val resultMessage = result.message.ifBlank {
                        if (result.success) "已添加到：${playlist.title}" else "添加失败"
                    }
                    setMessage(resultMessage)
                    state.localPlaylistOperationError = result.message.takeUnless { result.success }
                    state.playlistOperationFeedback = resultMessage
                    if (result.success) {
                        result.playlist?.let(::replacePlaylist)
                    }
                    closeTargetPicker()
                }
                .onFailure {
                    state.localPlaylistOperationError = it.message ?: "添加失败"
                    onError(it)
                }
            setLoading(false)
        }
    }

    fun open(playlist: LocalPlaylist) {
        navigator.navigate(AppRoute.LocalPlaylist)
        state.selectedLocalPlaylist = playlist
        state.selectedLocalPlaylistTracks = toMusicTracks(playlist)
        state.selectedLocalPlaylistError = null
    }

    fun close() {
        navigator.pop(AppRoute.LocalPlaylist)
        state.selectedLocalPlaylist = null
        state.selectedLocalPlaylistTracks = emptyList()
        state.selectedLocalPlaylistError = null
    }

    fun canRemove(track: MusicTrack): Boolean {
        val playlist = state.selectedLocalPlaylist ?: return false
        val localTrack = toLocalPlaylistTrack(track) ?: return false
        return playlist.tracks.any { it.uri == localTrack.uri }
    }

    fun remove(track: MusicTrack) {
        val playlist = state.selectedLocalPlaylist ?: return
        val localTrack = toLocalPlaylistTrack(track) ?: return
        if (!canRemove(track)) return
        scope.launch {
            setLoading(true)
            setMessage("正在从本地歌单移除")
            runCatching { repository.removeTrack(playlist, localTrack.uri) }
                .onSuccess { result ->
                    if (result.success) {
                        val updated = result.playlist ?: playlist.copy(
                            tracks = playlist.tracks.filterNot { it.uri == localTrack.uri },
                        )
                        replacePlaylist(updated)
                        val resultMessage = result.message.ifBlank { "已从本地歌单移除" }
                        setMessage(resultMessage)
                        state.playlistOperationFeedback = resultMessage
                    } else {
                        state.selectedLocalPlaylistError = result.message.ifBlank { "移除失败" }
                    }
                }
                .onFailure {
                    state.selectedLocalPlaylistError = it.message ?: "移除失败"
                    onError(it)
                }
            setLoading(false)
        }
    }

    fun canDeleteSelected(): Boolean = state.selectedLocalPlaylist != null

    fun deleteSelected() {
        val playlist = state.selectedLocalPlaylist ?: return
        scope.launch {
            setLoading(true)
            setMessage("正在删除本地歌单")
            runCatching { repository.delete(playlist) }
                .onSuccess { result ->
                    setMessage(result.message.ifBlank { if (result.success) "歌单已删除" else "删除歌单失败" })
                    if (result.success) {
                        close()
                        state.localPlaylists = state.localPlaylists.filterNot { it.id == playlist.id }
                    }
                }
                .onFailure(onError)
            setLoading(false)
        }
    }

    fun prepareImport(fileName: String, content: String) {
        val preview = LocalPlaylistFileCodec.decode(fileName, content)
        state.localPlaylistImportPreview = preview
        setMessage(
            if (preview.skippedLineCount > 0) {
                "已读取 ${preview.tracks.size} 首歌曲，跳过 ${preview.skippedLineCount} 行"
            } else {
                "请选择导入方式"
            }
        )
    }

    fun existingForImport(preview: LocalPlaylistImportPreview): LocalPlaylist? =
        state.localPlaylists.firstOrNull { it.title.trim() == preview.title.trim() }

    fun cancelImport() {
        state.localPlaylistImportPreview = null
    }

    fun importPlaylist(mode: LocalPlaylistImportMode, replacePlaylistId: String? = null) {
        val preview = state.localPlaylistImportPreview ?: return
        val replacePlaylist = replacePlaylistId?.let { id -> state.localPlaylists.firstOrNull { it.id == id } }
        scope.launch {
            setLoading(true)
            setMessage("正在导入本地歌单")
            runCatching { repository.importPlaylist(preview, mode, replacePlaylist) }
                .onSuccess { result ->
                    setMessage(result.message.ifBlank { if (result.success) "歌单已导入" else "导入失败" })
                    if (result.success) {
                        state.localPlaylistImportPreview = null
                        refreshInternal(showMessage = false)
                    }
                }
                .onFailure(onError)
            setLoading(false)
        }
    }

    fun exportSelected(onReady: (LocalPlaylistFile) -> Unit) {
        val playlist = state.selectedLocalPlaylist ?: return
        scope.launch {
            setLoading(true)
            setMessage("正在准备导出文件")
            runCatching { repository.export(playlist) }
                .onSuccess(onReady)
                .onFailure(onError)
            setLoading(false)
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
}
