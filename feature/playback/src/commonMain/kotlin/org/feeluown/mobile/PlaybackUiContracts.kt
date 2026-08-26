package org.feeluown.mobile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val EMPTY_PLAYBACK_FEEDBACK_FLOW: StateFlow<String?> = MutableStateFlow(null)

enum class PlaybackContextType {
    Playlist,
    Feature,
    Album,
    Artist,
    Search,
    LocalDirectory,
}

data class PlaybackContextSnapshot(
    val type: PlaybackContextType,
    val sourceId: String,
    val resourceId: String,
    val title: String,
    val subtitle: String = "",
    val coverUrl: String? = null,
)

/** UI-only player navigation state implemented by the application presentation layer. */
interface PlaybackNavigationPort {
    val isFullPlayerOpen: Boolean
    val isQueueOpen: Boolean

    fun openFullPlayer()
    fun closeFullPlayer()
    fun toggleQueue()
}

/** Queue state and queue-edit actions owned by the playback feature. */
interface PlaybackQueueUiPort {
    val currentQueueTrack: MusicTrack?
    val queue: List<MusicTrack>
    val displayUpNextCount: Int
    val isShuffleEnabled: Boolean
    val repeatMode: RepeatMode
    val isFmQueueActive: Boolean
    val trackChangeDirection: TrackChangeDirection
    val queueStateFlow: StateFlow<PlaybackQueueState>?
        get() = null
    /** Read model exposed by the app playback binding; physical playback owners default to none. */
    val listeningHistoryRepository: ListeningHistoryRepository?
        get() = null
    val feedback: StateFlow<String?>
        get() = EMPTY_PLAYBACK_FEEDBACK_FLOW

    fun playTracks(tracks: List<MusicTrack>, index: Int)

    fun playTracks(tracks: List<MusicTrack>, index: Int, context: PlaybackContextSnapshot) =
        playTracks(tracks, index)

    fun playPlaylistTracks(tracks: List<MusicTrack>, index: Int, sourcePlaylistId: String) =
        playTracks(tracks, index)

    fun playPlaylistTracks(
        tracks: List<MusicTrack>,
        index: Int,
        sourcePlaylistId: String,
        context: PlaybackContextSnapshot,
    ) = playPlaylistTracks(tracks, index, sourcePlaylistId)

    fun playAllPlaylistTracks(tracks: List<MusicTrack>, sourcePlaylistId: String) =
        playPlaylistTracks(tracks, 0, sourcePlaylistId)

    fun playAllPlaylistTracks(
        tracks: List<MusicTrack>,
        sourcePlaylistId: String,
        context: PlaybackContextSnapshot,
    ) = playPlaylistTracks(tracks, 0, sourcePlaylistId, context)

    fun appendPlaylistTracks(sourcePlaylistId: String, tracks: List<MusicTrack>) = Unit

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
    val sleepTimerStateFlow: StateFlow<SleepTimerState>?
        get() = null
    val feedback: StateFlow<String?>
        get() = EMPTY_PLAYBACK_FEEDBACK_FLOW

    fun setSleepTimerDurationMinutes(minutes: Int)
    fun clearSleepTimer()
    fun setSleepTimerToEndOfTrack()
    fun dismissFeedback(feedback: String) = Unit
}

data class LyricsAssociationUiState(
    val trackId: String? = null,
    val isLyricsUnavailable: Boolean = false,
    val isManualAssociation: Boolean = false,
    val associatedTrackId: String? = null,
    val associatedTrackTitle: String? = null,
    val alignmentOffsetMs: Long = 0L,
    val isSearchOpen: Boolean = false,
    val query: String = "",
    val results: List<MusicTrack> = emptyList(),
    val isSearching: Boolean = false,
    val selectingTrackId: String? = null,
    val message: String? = null,
)

interface PlaybackLyricsPort {
    val associationState: StateFlow<LyricsAssociationUiState>

    fun openAssociationSearch(track: MusicTrack)
    fun updateAssociationQuery(query: String)
    fun searchAssociation()
    fun selectAssociation(track: MusicTrack)
    fun closeAssociationSearch()
    fun updateAlignmentOffset(offsetMs: Long)
}

interface ReplacementActionPort {
    val replacementCandidateState: ReplacementCandidateState
    val replacementCandidateStateFlow: StateFlow<ReplacementCandidateState>?
        get() = null

    fun loadReplacementCandidates(track: MusicTrack)
    fun selectReplacementCandidate(track: MusicTrack, candidate: ReplacementCandidate)
    fun openReplacementTrackDetail(track: MusicTrack)
}
