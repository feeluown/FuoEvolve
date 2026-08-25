package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class PlaylistControllerState {
    private var localPlaylistChangeListener: (() -> Unit)? = null

    private var localPlaylistsState: List<LocalPlaylist> = emptyList()
    var localPlaylists: List<LocalPlaylist>
        get() = localPlaylistsState
        set(value) {
            localPlaylistsState = value
            localPlaylistChanged()
        }

    private var selectedLocalPlaylistState: LocalPlaylist? = null
    var selectedLocalPlaylist: LocalPlaylist?
        get() = selectedLocalPlaylistState
        set(value) {
            selectedLocalPlaylistState = value
            localPlaylistChanged()
        }

    private var selectedLocalPlaylistTracksState: List<MusicTrack> = emptyList()
    var selectedLocalPlaylistTracks: List<MusicTrack>
        get() = selectedLocalPlaylistTracksState
        set(value) {
            selectedLocalPlaylistTracksState = value
            localPlaylistChanged()
        }

    private var selectedLocalPlaylistErrorState: String? = null
    var selectedLocalPlaylistError: String?
        get() = selectedLocalPlaylistErrorState
        set(value) {
            selectedLocalPlaylistErrorState = value
            localPlaylistChanged()
        }

    private var playlistTargetTrackState: MusicTrack? = null
    var playlistTargetTrack: MusicTrack?
        get() = playlistTargetTrackState
        set(value) {
            playlistTargetTrackState = value
            playlistTargetPickerChanged()
        }

    private var playlistTargetTypeState = PlaylistTargetType.Provider
    var playlistTargetType: PlaylistTargetType
        get() = playlistTargetTypeState
        set(value) {
            playlistTargetTypeState = value
            playlistTargetPickerChanged()
        }

    private var playlistTargetPickerShowSwitcherState = true
    var playlistTargetPickerShowSwitcher: Boolean
        get() = playlistTargetPickerShowSwitcherState
        set(value) {
            playlistTargetPickerShowSwitcherState = value
            playlistTargetPickerChanged()
        }

    private var playlistOperationTargetsState: List<ProviderPlaylist> = emptyList()
    var playlistOperationTargets: List<ProviderPlaylist>
        get() = playlistOperationTargetsState
        set(value) {
            playlistOperationTargetsState = value
            playlistTargetPickerChanged()
        }

    private var playlistOperationErrorState: String? = null
    var playlistOperationError: String?
        get() = playlistOperationErrorState
        set(value) {
            playlistOperationErrorState = value
            playlistTargetPickerChanged()
        }

    private val mutablePlaylistTargetPickerState = MutableStateFlow(PlaylistTargetPickerUiState())
    val playlistTargetPickerFlow: StateFlow<PlaylistTargetPickerUiState> =
        mutablePlaylistTargetPickerState.asStateFlow()

    private val mutablePlaylistOperationFeedback = MutableStateFlow<String?>(null)
    val playlistOperationFeedbackFlow: StateFlow<String?> = mutablePlaylistOperationFeedback.asStateFlow()
    var playlistOperationFeedback: String?
        get() = mutablePlaylistOperationFeedback.value
        set(value) {
            mutablePlaylistOperationFeedback.value = value
        }

    private var localPlaylistOperationErrorState: String? = null
    var localPlaylistOperationError: String?
        get() = localPlaylistOperationErrorState
        set(value) {
            localPlaylistOperationErrorState = value
            localPlaylistChanged()
            playlistTargetPickerChanged()
        }

    private var localPlaylistImportPreviewState: LocalPlaylistImportPreview? = null
    var localPlaylistImportPreview: LocalPlaylistImportPreview?
        get() = localPlaylistImportPreviewState
        set(value) {
            localPlaylistImportPreviewState = value
            localPlaylistChanged()
        }

    fun observeLocalPlaylistChanges(listener: () -> Unit) {
        localPlaylistChangeListener = listener
        listener()
    }

    private fun localPlaylistChanged() {
        localPlaylistChangeListener?.invoke()
    }

    private fun playlistTargetPickerChanged() {
        mutablePlaylistTargetPickerState.value = PlaylistTargetPickerUiState(
            track = playlistTargetTrackState,
            targetType = playlistTargetTypeState,
            showSwitcher = playlistTargetPickerShowSwitcherState,
            providerTargets = playlistOperationTargetsState,
            providerError = playlistOperationErrorState,
            localError = localPlaylistOperationErrorState,
        )
    }
}
