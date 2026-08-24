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

/** UI-only player navigation state. */
interface PlaybackNavigationPort {
    val isFullPlayerOpen: Boolean
    val isQueueOpen: Boolean

    fun openFullPlayer()
    fun closeFullPlayer()
    fun toggleQueue()
}

/** Rich playback presentation that intentionally stays outside the narrow PlaybackSession API. */
interface PlaybackPresentationPort {
    val currentTrack: MusicTrack?
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

/** Queue state and queue-edit actions owned by the playback feature. */
interface PlaybackQueueUiPort {
    /** Durable queue-owned track, including restored state and queue-side metadata updates. */
    val currentQueueTrack: MusicTrack?
    val queue: List<MusicTrack>
    val displayUpNextCount: Int
    val isShuffleEnabled: Boolean
    val repeatMode: RepeatMode
    val isFmQueueActive: Boolean
    val trackChangeDirection: TrackChangeDirection
    val feedback: StateFlow<String?>
        get() = EMPTY_FEEDBACK_FLOW

    /** Replace the active source queue and start the selected item through the queue owner. */
    fun playTracks(tracks: List<MusicTrack>, index: Int)

    /**
     * Replace the active queue from a durable playlist while preserving playlist playback context.
     * Implementations that do not track playlist context can safely fall back to [playTracks].
     */
    fun playPlaylistTracks(tracks: List<MusicTrack>, index: Int, sourcePlaylistId: String) =
        playTracks(tracks, index)

    /**
     * Start a durable playlist with Play All semantics instead of pinning a selected item.
     * The distinction matters while shuffle is enabled: selecting a row keeps that row first,
     * while Play All starts from a newly shuffled queue, matching the pre-refactor behavior.
     */
    fun playAllPlaylistTracks(tracks: List<MusicTrack>, sourcePlaylistId: String) =
        playPlaylistTracks(tracks, 0, sourcePlaylistId)

    /** Append lazily loaded playlist tracks only when the same playlist still owns the queue. */
    fun appendPlaylistTracks(sourcePlaylistId: String, tracks: List<MusicTrack>) = Unit

    /**
     * Replace the active queue from a provider feature while preserving dynamic-queue identity.
     * This is required by FM/daily-recommendation style features whose queue is extended lazily.
     */
    fun playFeatureTracks(tracks: List<MusicTrack>, index: Int, sourceFeature: ProviderFeature) =
        playTracks(tracks, index)

    fun toggleShuffle()
    fun toggleRepeat()
    fun clearQueue()
    fun playQueueIndex(index: Int)
    fun removeFromQueue(track: MusicTrack)
    fun playPlaybackPart(index: Int)
    fun addToUpNext(track: MusicTrack)
    fun dismissFeedback(feedback: String) = Unit
}

/** Sleep-timer state, commands and transient feedback owned by playback. */
interface PlaybackSleepTimerPort {
    val sleepTimerState: SleepTimerState
    val feedback: StateFlow<String?>
        get() = EMPTY_FEEDBACK_FLOW

    fun setSleepTimerDurationMinutes(minutes: Int)
    fun clearSleepTimer()
    fun setSleepTimerToEndOfTrack()
    fun dismissFeedback(feedback: String) = Unit
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

data class LyricsAssociationUiState(
    val trackId: String? = null,
    val isLyricsUnavailable: Boolean = false,
    val isManualAssociation: Boolean = false,
    val associatedTrackId: String? = null,
    val associatedTrackTitle: String? = null,
    val isSearchOpen: Boolean = false,
    val query: String = "",
    val results: List<MusicTrack> = emptyList(),
    val isSearching: Boolean = false,
    val selectingTrackId: String? = null,
    val message: String? = null,
)

/** Manual lyric lookup/association state owned by playback. */
interface PlaybackLyricsPort {
    val associationState: StateFlow<LyricsAssociationUiState>

    fun openAssociationSearch(track: MusicTrack)
    fun updateAssociationQuery(query: String)
    fun searchAssociation()
    fun selectAssociation(track: MusicTrack)
    fun closeAssociationSearch()
}

/** Smart-replacement state/actions used by the now-playing surface. */
interface ReplacementActionPort {
    val replacementCandidateState: ReplacementCandidateState

    fun loadReplacementCandidates(track: MusicTrack)
    fun selectReplacementCandidate(track: MusicTrack, candidate: ReplacementCandidate)
    fun openReplacementTrackDetail(track: MusicTrack)
}