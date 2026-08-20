package org.feeluown.mobile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class LocalMusicUiState(
    val tracks: List<MusicTrack> = emptyList(),
    val viewMode: LocalMusicViewMode = LocalMusicViewMode.All,
    val directories: List<LocalMusicDirectory> = emptyList(),
    val selectedDirectoryId: String? = null,
    val selectedCollection: LocalMusicCollectionSelection? = null,
    val excludedDirectoryIds: Set<String> = emptySet(),
    val minDurationSeconds: Int = DEFAULT_LOCAL_MUSIC_MIN_DURATION_SECONDS,
    val metadataEditorTrack: MusicTrack? = null,
    val metadataProviders: List<ProviderInfo> = emptyList(),
    val selectedMetadataProviderId: String? = null,
    val metadataSearchResults: List<MusicTrack> = emptyList(),
    val metadataSearchMessage: String? = null,
    val isLoading: Boolean = false,
    val message: String? = null,
    val errorMessage: String? = null,
)

data class LocalTrackUpdate(
    val previousTrackId: String,
    val track: MusicTrack,
)

/**
 * Feature state with Compose compatibility properties for the remaining facade callers.
 * [LocalMusicController] publishes the same state as immutable [LocalMusicUiState] for
 * production feature UI.
 */
internal class LocalMusicControllerState {
    private var changeListener: (() -> Unit)? = null

    private var tracksState by mutableStateOf<List<MusicTrack>>(emptyList())
    var tracks: List<MusicTrack>
        get() = tracksState
        set(value) {
            tracksState = value
            changed()
        }

    private var viewModeState by mutableStateOf(LocalMusicViewMode.All)
    var viewMode: LocalMusicViewMode
        get() = viewModeState
        set(value) {
            viewModeState = value
            changed()
        }

    private var directoriesState by mutableStateOf<List<LocalMusicDirectory>>(emptyList())
    var directories: List<LocalMusicDirectory>
        get() = directoriesState
        set(value) {
            directoriesState = value
            changed()
        }

    private var selectedDirectoryIdState by mutableStateOf<String?>(null)
    var selectedDirectoryId: String?
        get() = selectedDirectoryIdState
        set(value) {
            selectedDirectoryIdState = value
            changed()
        }

    private var selectedCollectionState by mutableStateOf<LocalMusicCollectionSelection?>(null)
    var selectedCollection: LocalMusicCollectionSelection?
        get() = selectedCollectionState
        set(value) {
            selectedCollectionState = value
            changed()
        }

    private var excludedDirectoryIdsState by mutableStateOf<Set<String>>(emptySet())
    var excludedDirectoryIds: Set<String>
        get() = excludedDirectoryIdsState
        set(value) {
            excludedDirectoryIdsState = value
            changed()
        }

    private var minDurationSecondsState by mutableStateOf(DEFAULT_LOCAL_MUSIC_MIN_DURATION_SECONDS)
    var minDurationSeconds: Int
        get() = minDurationSecondsState
        set(value) {
            minDurationSecondsState = value
            changed()
        }

    private var metadataEditorTrackState by mutableStateOf<MusicTrack?>(null)
    var metadataEditorTrack: MusicTrack?
        get() = metadataEditorTrackState
        set(value) {
            metadataEditorTrackState = value
            changed()
        }

    private var selectedMetadataProviderIdState by mutableStateOf<String?>(null)
    var selectedMetadataProviderId: String?
        get() = selectedMetadataProviderIdState
        set(value) {
            selectedMetadataProviderIdState = value
            changed()
        }

    private var metadataSearchResultsState by mutableStateOf<List<MusicTrack>>(emptyList())
    var metadataSearchResults: List<MusicTrack>
        get() = metadataSearchResultsState
        set(value) {
            metadataSearchResultsState = value
            changed()
        }

    private var metadataSearchMessageState by mutableStateOf<String?>(null)
    var metadataSearchMessage: String?
        get() = metadataSearchMessageState
        set(value) {
            metadataSearchMessageState = value
            changed()
        }

    // Transitional alias used by the compatibility facade.
    var localMetadataSearchMessage: String?
        get() = metadataSearchMessage
        set(value) {
            metadataSearchMessage = value
        }

    fun observeChanges(listener: () -> Unit) {
        changeListener = listener
        listener()
    }

    fun toUiState(base: LocalMusicUiState = LocalMusicUiState()): LocalMusicUiState = base.copy(
        tracks = tracks,
        viewMode = viewMode,
        directories = directories,
        selectedDirectoryId = selectedDirectoryId,
        selectedCollection = selectedCollection,
        excludedDirectoryIds = excludedDirectoryIds,
        minDurationSeconds = minDurationSeconds,
        metadataEditorTrack = metadataEditorTrack,
        selectedMetadataProviderId = selectedMetadataProviderId,
        metadataSearchResults = metadataSearchResults,
        metadataSearchMessage = metadataSearchMessage,
    )

    private fun changed() {
        changeListener?.invoke()
    }
}
