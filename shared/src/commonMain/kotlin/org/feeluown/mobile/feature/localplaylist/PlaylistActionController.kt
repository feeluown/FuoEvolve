package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Owns provider/local playlist mutations initiated from the now-playing surface. */
internal class PlaylistActionController(
    private val providerRepository: ProviderMusicRepository,
    private val localPlaylistController: LocalPlaylistController,
    private val state: PlaylistControllerState,
    private val scope: CoroutineScope,
    private val selectedPlaylist: () -> ProviderPlaylist?,
    private val selectedPlaylistCategory: () -> ProviderFeatureCategory?,
    private val selectedPlaylistTracks: () -> List<MusicTrack>,
    private val updateSelectedPlaylistTracks: (List<MusicTrack>) -> Unit,
    private val updateSelectedPlaylistError: (String?) -> Unit,
    private val providerCapabilities: () -> Map<String, ProviderCapabilities>,
    private val isProviderLoggedIn: (String) -> Boolean,
    private val providerName: (String) -> String,
    private val refreshAfterProviderMutation: (String) -> Unit,
    private val setLoading: (Boolean) -> Unit,
    private val setMessage: (String) -> Unit,
    private val onError: (Throwable) -> Unit,
) : PlaylistActionPort {
    override val feedback: StateFlow<String?> = state.playlistOperationFeedbackFlow
    override val targetPickerState: StateFlow<PlaylistTargetPickerUiState> = state.playlistTargetPickerFlow

    override fun canAddTrackToPlaylist(track: MusicTrack): Boolean =
        canAddTrackToProviderPlaylist(track) || canAddTrackToLocalPlaylist(track)

    override fun canAddTrackToProviderPlaylist(track: MusicTrack): Boolean {
        val providerId = trackProviderId(track) ?: return false
        return track.sourceType == TrackSourceType.Provider &&
            isProviderLoggedIn(providerId) &&
            providerCapabilities()[providerId]?.canAddSongToPlaylist == true
    }

    override fun canAddTrackToLocalPlaylist(track: MusicTrack): Boolean =
        localPlaylistController.canAddTrack(track)

    override fun openPlaylistTargetPicker(track: MusicTrack) {
        if (!canAddTrackToPlaylist(track)) return
        state.playlistTargetTrack = track
        state.playlistTargetType = if (canAddTrackToProviderPlaylist(track)) {
            PlaylistTargetType.Provider
        } else {
            PlaylistTargetType.Local
        }
        state.playlistTargetPickerShowSwitcher = true
        state.playlistOperationTargets = emptyList()
        state.playlistOperationError = null
        state.localPlaylistOperationError = if (state.localPlaylists.isEmpty()) "请先新建本地歌单" else null
        if (!canAddTrackToProviderPlaylist(track)) return
        scope.launch {
            setLoading(true)
            setMessage("正在加载可添加歌单")
            runCatching { providerRepository.playlistOperationTargets(track) }
                .onSuccess {
                    state.playlistOperationTargets = it
                    state.playlistOperationError = if (it.isEmpty()) "没有可添加的歌单" else null
                    setMessage(if (it.isEmpty()) "没有可添加的歌单" else "请选择目标歌单")
                }
                .onFailure {
                    state.playlistOperationError = it.message ?: it::class.simpleName.orEmpty()
                    onError(it)
                }
            setLoading(false)
        }
    }

    override fun closePlaylistTargetPicker() {
        state.playlistTargetTrack = null
        state.playlistTargetType = PlaylistTargetType.Provider
        state.playlistTargetPickerShowSwitcher = true
        state.playlistOperationTargets = emptyList()
        state.playlistOperationError = null
        state.localPlaylistOperationError = null
    }

    override fun playlistProviderName(track: MusicTrack): String {
        val providerId = trackProviderId(track)
        return state.playlistOperationTargets.firstOrNull()?.providerName
            ?.takeIf { it.isNotBlank() }
            ?: providerId?.let(providerName)?.takeIf { it.isNotBlank() && it != providerId }
            ?: track.providerName?.takeIf { it.isNotBlank() }
            ?: providerId.orEmpty().ifBlank { "Provider" }
    }

    override fun selectPlaylistTargetType(type: PlaylistTargetType) {
        val track = state.playlistTargetTrack ?: return
        if (type == PlaylistTargetType.Provider && !canAddTrackToProviderPlaylist(track)) return
        if (type == PlaylistTargetType.Local && !canAddTrackToLocalPlaylist(track)) return
        state.playlistTargetType = type
        if (type == PlaylistTargetType.Local) {
            state.localPlaylistOperationError = if (state.localPlaylists.isEmpty()) "请先新建本地歌单" else null
        }
    }

    override fun addTrackToProviderPlaylist(playlist: ProviderPlaylist) {
        val track = state.playlistTargetTrack ?: return
        scope.launch {
            setLoading(true)
            setMessage("正在添加到歌单")
            runCatching { providerRepository.addTrackToPlaylist(playlist, track) }
                .onSuccess { result ->
                    if (result.success) {
                        val feedback = result.message.ifBlank { "已添加到：${playlist.title}" }
                        setMessage(feedback)
                        updateFeedback(feedback)
                        closePlaylistTargetPicker()
                        refreshAfterProviderMutation(playlist.providerId)
                    } else {
                        state.playlistOperationError = result.message.ifBlank { "添加失败" }
                        setMessage(state.playlistOperationError.orEmpty())
                        updateFeedback(state.playlistOperationError)
                    }
                }
                .onFailure {
                    val feedback = playlistMutationErrorMessage(it, playlist.providerId)
                    state.playlistOperationError = feedback
                    onError(it)
                    updateFeedback(feedback)
                }
            setLoading(false)
        }
    }

    override fun addTrackToLocalPlaylist(playlist: LocalPlaylist) =
        localPlaylistController.addTargetTrackTo(playlist)

    override fun canRemoveTrackFromSelectedPlaylist(track: MusicTrack): Boolean {
        val playlist = selectedPlaylist() ?: return false
        return track.sourceType == TrackSourceType.Provider &&
            selectedPlaylistCategory() == ProviderFeatureCategory.MinePlaylists &&
            trackProviderId(track) == playlist.providerId &&
            isProviderLoggedIn(playlist.providerId) &&
            providerCapabilities()[playlist.providerId]?.canRemoveSongFromPlaylist == true
    }

    override fun removeTrackFromSelectedPlaylist(track: MusicTrack) {
        val playlist = selectedPlaylist() ?: return
        if (!canRemoveTrackFromSelectedPlaylist(track)) return
        scope.launch {
            setLoading(true)
            setMessage("正在从歌单移除")
            runCatching { providerRepository.removeTrackFromPlaylist(playlist, track) }
                .onSuccess { result ->
                    if (result.success) {
                        updateSelectedPlaylistTracks(selectedPlaylistTracks().filterNot { it.id == track.id })
                        val feedback = result.message.ifBlank { "已从歌单移除：${track.title}" }
                        setMessage(feedback)
                        updateFeedback(feedback)
                        refreshAfterProviderMutation(playlist.providerId)
                    } else {
                        val error = result.message.ifBlank { "移除失败" }
                        updateSelectedPlaylistError(error)
                        setMessage(error)
                        updateFeedback(error)
                    }
                }
                .onFailure {
                    val feedback = playlistMutationErrorMessage(it, playlist.providerId)
                    onError(it)
                    updateFeedback(feedback)
                }
            setLoading(false)
        }
    }

    override fun dismissFeedback(feedback: String) {
        if (state.playlistOperationFeedback == feedback) {
            updateFeedback(null)
        }
    }

    private fun updateFeedback(feedback: String?) {
        state.playlistOperationFeedback = feedback
    }

    private fun trackProviderId(track: MusicTrack): String? =
        track.source.takeIf { it.isNotBlank() }
            ?: track.providerId?.substringBefore(":")?.takeIf { it.isNotBlank() }
}

internal fun playlistMutationErrorMessage(
    throwable: Throwable,
    providerId: String,
): String = throwable.providerFailureOrNull(providerId)?.userMessage
    ?: throwable.message
    ?: throwable::class.simpleName.orEmpty().ifBlank { "操作失败" }
