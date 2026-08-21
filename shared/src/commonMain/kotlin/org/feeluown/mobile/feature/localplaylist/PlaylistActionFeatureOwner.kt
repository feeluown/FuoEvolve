package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

fun createPlaylistActionPort(
    providerRepository: ProviderMusicRepository,
    providerCatalog: ProviderCatalogFeatureController,
    providerDetails: ProviderDetailOwners,
    localPlaylist: LocalPlaylistFeatureOwner,
    scope: CoroutineScope,
    onProviderMutation: (String) -> Unit = {},
): PlaylistActionPort = DefaultPlaylistActionOwner(
    providerRepository = providerRepository,
    providerCatalog = providerCatalog,
    providerDetails = providerDetails,
    localPlaylist = localPlaylist,
    scope = scope,
    onProviderMutation = onProviderMutation,
)

private class DefaultPlaylistActionOwner(
    private val providerRepository: ProviderMusicRepository,
    private val providerCatalog: ProviderCatalogFeatureController,
    private val providerDetails: ProviderDetailOwners,
    private val localPlaylist: LocalPlaylistFeatureOwner,
    private val scope: CoroutineScope,
    private val onProviderMutation: (String) -> Unit,
) : PlaylistActionPort {
    private val mutableFeedback = MutableStateFlow<String?>(null)
    override val feedback: StateFlow<String?> = mutableFeedback.asStateFlow()

    private val mutableTargetPickerState = MutableStateFlow(PlaylistTargetPickerUiState())
    override val targetPickerState: StateFlow<PlaylistTargetPickerUiState> = mutableTargetPickerState.asStateFlow()

    override fun canAddTrackToPlaylist(track: MusicTrack): Boolean =
        canAddTrackToProviderPlaylist(track) || canAddTrackToLocalPlaylist(track)

    override fun canAddTrackToProviderPlaylist(track: MusicTrack): Boolean {
        val providerId = trackProviderId(track) ?: return false
        val catalog = providerCatalog.uiState.value
        return track.sourceType == TrackSourceType.Provider &&
            catalog.sessions.authStates[providerId]?.isLoggedIn == true &&
            catalog.capabilities[providerId]?.canAddSongToPlaylist == true
    }

    override fun canAddTrackToLocalPlaylist(track: MusicTrack): Boolean = localPlaylist.canAddTrack(track)

    override fun openPlaylistTargetPicker(track: MusicTrack) {
        val canProvider = canAddTrackToProviderPlaylist(track)
        val canLocal = canAddTrackToLocalPlaylist(track)
        if (!canProvider && !canLocal) return
        mutableTargetPickerState.value = PlaylistTargetPickerUiState(
            track = track,
            targetType = if (canProvider) PlaylistTargetType.Provider else PlaylistTargetType.Local,
            showSwitcher = canProvider && canLocal,
            localError = "请先新建本地歌单".takeIf { localPlaylist.uiState.value.playlists.isEmpty() },
        )
        if (!canProvider) return
        scope.launch {
            runCatching { providerRepository.playlistOperationTargets(track) }
                .onSuccess { targets ->
                    if (targetPickerState.value.track?.id == track.id) {
                        mutableTargetPickerState.value = targetPickerState.value.copy(
                            providerTargets = targets,
                            providerError = "没有可添加的歌单".takeIf { targets.isEmpty() },
                        )
                    }
                }
                .onFailure { throwable ->
                    if (targetPickerState.value.track?.id == track.id) {
                        mutableTargetPickerState.value = targetPickerState.value.copy(
                            providerError = throwable.providerFailureOrNull(trackProviderId(track))?.userMessage
                                ?: throwable.message
                                ?: "加载可添加歌单失败",
                        )
                    }
                }
        }
    }

    override fun closePlaylistTargetPicker() {
        mutableTargetPickerState.value = PlaylistTargetPickerUiState()
    }

    override fun playlistProviderName(track: MusicTrack): String {
        val providerId = trackProviderId(track).orEmpty()
        val catalog = providerCatalog.uiState.value
        return targetPickerState.value.providerTargets.firstOrNull()?.providerName
            ?.takeIf { it.isNotBlank() }
            ?: catalog.providers.firstOrNull { it.providerId == providerId }?.providerName
            ?: catalog.availableProviders.firstOrNull { it.providerId == providerId }?.providerName
            ?: track.providerName?.takeIf { it.isNotBlank() }
            ?: providerId.ifBlank { "Provider" }
    }

    override fun selectPlaylistTargetType(type: PlaylistTargetType) {
        val track = targetPickerState.value.track ?: return
        if (type == PlaylistTargetType.Provider && !canAddTrackToProviderPlaylist(track)) return
        if (type == PlaylistTargetType.Local && !canAddTrackToLocalPlaylist(track)) return
        mutableTargetPickerState.value = targetPickerState.value.copy(
            targetType = type,
            localError = if (type == PlaylistTargetType.Local && localPlaylist.uiState.value.playlists.isEmpty()) {
                "请先新建本地歌单"
            } else {
                null
            },
        )
    }

    override fun addTrackToProviderPlaylist(playlist: ProviderPlaylist) {
        val track = targetPickerState.value.track ?: return
        if (!canAddTrackToProviderPlaylist(track)) return
        scope.launch {
            runCatching { providerRepository.addTrackToPlaylist(playlist, track) }
                .onSuccess { result ->
                    val message = result.message.ifBlank {
                        if (result.success) "已添加到：${playlist.title}" else "添加失败"
                    }
                    mutableFeedback.value = message
                    if (result.success) {
                        closePlaylistTargetPicker()
                        onProviderMutation(playlist.providerId)
                    } else {
                        mutableTargetPickerState.value = targetPickerState.value.copy(providerError = message)
                    }
                }
                .onFailure { throwable ->
                    val message = playlistMutationErrorMessage(throwable, playlist.providerId)
                    mutableFeedback.value = message
                    mutableTargetPickerState.value = targetPickerState.value.copy(providerError = message)
                }
        }
    }

    override fun addTrackToLocalPlaylist(playlist: LocalPlaylist) {
        val track = targetPickerState.value.track ?: return
        if (!localPlaylist.canAddTrack(track)) return
        scope.launch {
            val result = localPlaylist.addTrack(playlist, track)
            val message = result.message.ifBlank {
                if (result.success) "已添加到：${playlist.title}" else "添加失败"
            }
            mutableFeedback.value = message
            if (result.success) {
                closePlaylistTargetPicker()
            } else if (targetPickerState.value.track?.id == track.id) {
                mutableTargetPickerState.value = targetPickerState.value.copy(localError = message)
            }
        }
    }

    override fun canRemoveTrackFromSelectedPlaylist(track: MusicTrack): Boolean =
        providerDetails.playlist.canRemove(track)

    override fun removeTrackFromSelectedPlaylist(track: MusicTrack) {
        providerDetails.playlist.remove(track)
    }

    override fun dismissFeedback(feedback: String) {
        if (mutableFeedback.value == feedback) mutableFeedback.value = null
    }

    private fun trackProviderId(track: MusicTrack): String? =
        track.source.takeIf { it.isNotBlank() }
            ?: track.providerId?.substringBefore(":")?.takeIf { it.isNotBlank() }
}
