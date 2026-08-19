package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class PlaylistControllerState {
    var localPlaylists by mutableStateOf<List<LocalPlaylist>>(emptyList())
    var selectedLocalPlaylist by mutableStateOf<LocalPlaylist?>(null)
    var selectedLocalPlaylistTracks by mutableStateOf<List<MusicTrack>>(emptyList())
    var selectedLocalPlaylistError by mutableStateOf<String?>(null)

    var playlistTargetTrack by mutableStateOf<MusicTrack?>(null)
    var playlistTargetType by mutableStateOf(PlaylistTargetType.Provider)
    var playlistTargetPickerShowSwitcher by mutableStateOf(true)
    var playlistOperationTargets by mutableStateOf<List<ProviderPlaylist>>(emptyList())
    var playlistOperationError by mutableStateOf<String?>(null)
    var playlistOperationFeedback by mutableStateOf<String?>(null)
    var localPlaylistOperationError by mutableStateOf<String?>(null)
    var localPlaylistImportPreview by mutableStateOf<LocalPlaylistImportPreview?>(null)
}
