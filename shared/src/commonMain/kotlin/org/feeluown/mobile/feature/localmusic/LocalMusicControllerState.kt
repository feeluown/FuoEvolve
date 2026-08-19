package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal class LocalMusicControllerState {
    var tracks by mutableStateOf<List<MusicTrack>>(emptyList())
    var viewMode by mutableStateOf(LocalMusicViewMode.All)
    var directories by mutableStateOf<List<LocalMusicDirectory>>(emptyList())
    var selectedDirectoryId by mutableStateOf<String?>(null)
    var selectedCollection by mutableStateOf<LocalMusicCollectionSelection?>(null)
    var excludedDirectoryIds by mutableStateOf<Set<String>>(emptySet())
    var minDurationSeconds by mutableStateOf(DEFAULT_LOCAL_MUSIC_MIN_DURATION_SECONDS)
    var metadataEditorTrack by mutableStateOf<MusicTrack?>(null)
    var selectedMetadataProviderId by mutableStateOf<String?>(null)
    var metadataSearchResults by mutableStateOf<List<MusicTrack>>(emptyList())
    var metadataSearchMessage by mutableStateOf<String?>(null)

    // Transitional alias used by the app facade while feature state is moved behind
    // dedicated state holders. Remove when the facade no longer delegates this field.
    var localMetadataSearchMessage: String?
        get() = metadataSearchMessage
        set(value) {
            metadataSearchMessage = value
        }
}
