package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal data class PlaylistControllerSnapshot(
    val localPlaylists: List<LocalPlaylist> = emptyList(),
    val selectedLocalPlaylist: LocalPlaylist? = null,
    val selectedLocalPlaylistTracks: List<MusicTrack> = emptyList(),
    val selectedLocalPlaylistError: String? = null,
    val playlistTargetTrack: MusicTrack? = null,
    val playlistTargetType: PlaylistTargetType = PlaylistTargetType.Provider,
    val playlistTargetPickerShowSwitcher: Boolean = true,
    val playlistOperationTargets: List<ProviderPlaylist> = emptyList(),
    val playlistOperationError: String? = null,
    val localPlaylistOperationError: String? = null,
    val localPlaylistImportPreview: LocalPlaylistImportPreview? = null,
)

internal class PlaylistControllerState {
    private var localPlaylistChangeListener: (() -> Unit)? = null
    private val mutableState = MutableStateFlow(PlaylistControllerSnapshot())
    val state: StateFlow<PlaylistControllerSnapshot> = mutableState.asStateFlow()

    var localPlaylists: List<LocalPlaylist>
        get() = mutableState.value.localPlaylists
        set(value) {
            update { it.copy(localPlaylists = value) }
            localPlaylistChanged()
        }

    var selectedLocalPlaylist: LocalPlaylist?
        get() = mutableState.value.selectedLocalPlaylist
        set(value) {
            update { it.copy(selectedLocalPlaylist = value) }
            localPlaylistChanged()
        }

    var selectedLocalPlaylistTracks: List<MusicTrack>
        get() = mutableState.value.selectedLocalPlaylistTracks
        set(value) {
            update { it.copy(selectedLocalPlaylistTracks = value) }
            localPlaylistChanged()
        }

    var selectedLocalPlaylistError: String?
        get() = mutableState.value.selectedLocalPlaylistError
        set(value) {
            update { it.copy(selectedLocalPlaylistError = value) }
            localPlaylistChanged()
        }

    var playlistTargetTrack: MusicTrack?
        get() = mutableState.value.playlistTargetTrack
        set(value) {
            update { it.copy(playlistTargetTrack = value) }
            playlistTargetPickerChanged()
        }

    var playlistTargetType: PlaylistTargetType
        get() = mutableState.value.playlistTargetType
        set(value) {
            update { it.copy(playlistTargetType = value) }
            playlistTargetPickerChanged()
        }

    var playlistTargetPickerShowSwitcher: Boolean
        get() = mutableState.value.playlistTargetPickerShowSwitcher
        set(value) {
            update { it.copy(playlistTargetPickerShowSwitcher = value) }
            playlistTargetPickerChanged()
        }

    var playlistOperationTargets: List<ProviderPlaylist>
        get() = mutableState.value.playlistOperationTargets
        set(value) {
            update { it.copy(playlistOperationTargets = value) }
            playlistTargetPickerChanged()
        }

    var playlistOperationError: String?
        get() = mutableState.value.playlistOperationError
        set(value) {
            update { it.copy(playlistOperationError = value) }
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

    var localPlaylistOperationError: String?
        get() = mutableState.value.localPlaylistOperationError
        set(value) {
            update { it.copy(localPlaylistOperationError = value) }
            localPlaylistChanged()
            playlistTargetPickerChanged()
        }

    var localPlaylistImportPreview: LocalPlaylistImportPreview?
        get() = mutableState.value.localPlaylistImportPreview
        set(value) {
            update { it.copy(localPlaylistImportPreview = value) }
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
        val current = mutableState.value
        mutablePlaylistTargetPickerState.value = PlaylistTargetPickerUiState(
            track = current.playlistTargetTrack,
            targetType = current.playlistTargetType,
            showSwitcher = current.playlistTargetPickerShowSwitcher,
            providerTargets = current.playlistOperationTargets,
            providerError = current.playlistOperationError,
            localError = current.localPlaylistOperationError,
        )
    }

    private inline fun update(crossinline transform: (PlaylistControllerSnapshot) -> PlaylistControllerSnapshot) {
        mutableState.update { current -> transform(current) }
    }
}
