package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val EMPTY_FEEDBACK_FLOW: StateFlow<String?> = MutableStateFlow(null)
private val EMPTY_DOWNLOAD_MANAGER_FLOW: StateFlow<DownloadManagerUiState> =
    MutableStateFlow(DownloadManagerUiState())
private val EMPTY_PLAYLIST_TARGET_PICKER_FLOW: StateFlow<PlaylistTargetPickerUiState> =
    MutableStateFlow(PlaylistTargetPickerUiState())
private val EMPTY_ARTIST_TARGET_PICKER_FLOW: StateFlow<ArtistTargetPickerUiState> =
    MutableStateFlow(ArtistTargetPickerUiState())

data class PlaylistTargetPickerUiState(
    val track: MusicTrack? = null,
    val targetType: PlaylistTargetType = PlaylistTargetType.Provider,
    val showSwitcher: Boolean = true,
    val providerTargets: List<ProviderPlaylist> = emptyList(),
    val providerError: String? = null,
    val localError: String? = null,
)

data class ArtistTargetPickerUiState(
    val track: MusicTrack? = null,
    val targets: List<TrackArtistTarget> = emptyList(),
)

/** Rich playback presentation that intentionally stays outside the narrow PlaybackSession API. */
interface PlaybackPresentationPort {
    val currentTrack: MusicTrack?
    val resolvedSource: ResolvedPlaybackSource?
        get() = null
    val playbackParts: List<PlaybackPart>
    val currentPartIndex: Int
    val lyricFontSize: LyricFontSize
    val themeMode: ThemeMode
    val dynamicCoverColorEnabled: Boolean
    val audioQuality: String?
    val audioFormatInfo: AudioFormatInfo?
    val audioDecoderInfo: AudioDecoderInfo?

    fun seekTo(positionMs: Long)
}

/** Download state/actions used by player UI and the download-manager feature. */
interface DownloadActionPort {
    val downloadStates: Map<String, DownloadState>
    val managerState: StateFlow<DownloadManagerUiState>
        get() = EMPTY_DOWNLOAD_MANAGER_FLOW

    fun download(track: MusicTrack)
    fun deleteDownload(track: MusicTrack)
    fun pause(taskId: String) = Unit
    fun resume(taskId: String) = Unit
    fun retry(taskId: String) = Unit
    fun deleteTask(taskId: String, deleteFile: Boolean) = Unit
    fun dismissQueueFeedback(feedback: String) = Unit
}

/** Playlist mutations and target-picker state used by playback and feature surfaces. */
interface PlaylistActionPort {
    val feedback: StateFlow<String?>
        get() = EMPTY_FEEDBACK_FLOW
    val targetPickerState: StateFlow<PlaylistTargetPickerUiState>
        get() = EMPTY_PLAYLIST_TARGET_PICKER_FLOW

    fun canAddTrackToPlaylist(track: MusicTrack): Boolean
    fun canAddTrackToProviderPlaylist(track: MusicTrack): Boolean = false
    fun canAddTrackToLocalPlaylist(track: MusicTrack): Boolean = false
    fun openPlaylistTargetPicker(track: MusicTrack)
    fun closePlaylistTargetPicker() = Unit
    fun playlistProviderName(track: MusicTrack): String = track.providerName ?: track.source
    fun selectPlaylistTargetType(type: PlaylistTargetType) = Unit
    fun addTrackToProviderPlaylist(playlist: ProviderPlaylist) = Unit
    fun addTrackToLocalPlaylist(playlist: LocalPlaylist) = Unit
    fun canRemoveTrackFromSelectedPlaylist(track: MusicTrack): Boolean
    fun removeTrackFromSelectedPlaylist(track: MusicTrack)
    fun dismissFeedback(feedback: String) = Unit
}

/** Provider-backed track navigation, artist-picker state and dislike actions. */
interface ProviderTrackActionPort {
    val artistTargetPickerState: StateFlow<ArtistTargetPickerUiState>
        get() = EMPTY_ARTIST_TARGET_PICKER_FLOW
    val feedback: StateFlow<String?>
        get() = EMPTY_FEEDBACK_FLOW

    fun openTrackArtist(track: MusicTrack)
    fun closeArtistTargetPicker() = Unit
    fun openArtistTarget(target: TrackArtistTarget) = Unit
    fun openTrackAlbum(track: MusicTrack)
    fun openOriginalTrackDetail(track: MusicTrack)
    fun canSetSongDisliked(track: MusicTrack): Boolean
    fun setSongDisliked(track: MusicTrack)
    fun dismissFeedback(feedback: String) = Unit
}

/** Local-library actions surfaced from now playing. */
interface LocalMusicActionPort {
    fun openLocalMetadataEditor(track: MusicTrack)
}
