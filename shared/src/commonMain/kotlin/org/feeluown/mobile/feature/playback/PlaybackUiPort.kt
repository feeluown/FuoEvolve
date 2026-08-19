package org.feeluown.mobile

/**
 * UI-specific playback presentation/actions that intentionally stay outside [PlaybackSession].
 *
 * [PlaybackSession] remains the authoritative transport/timing/status contract. This port carries
 * richer app models and presentation operations needed by FullPlayer/queue/replacement UI while
 * those owners are still being extracted from the legacy coordinator.
 */
interface PlaybackUiPort {
    val isFullPlayerOpen: Boolean
    val isQueueOpen: Boolean
    val trackChangeDirection: TrackChangeDirection
    val currentTrack: MusicTrack?
    val queue: List<MusicTrack>
    val playbackParts: List<PlaybackPart>
    val currentPartIndex: Int
    val displayUpNextCount: Int
    val isShuffleEnabled: Boolean
    val repeatMode: RepeatMode
    val isFmQueueActive: Boolean
    val sleepTimerState: SleepTimerState
    val lyricFontSize: LyricFontSize
    val themeMode: ThemeMode
    val dynamicCoverColorEnabled: Boolean
    val audioQuality: String?
    val audioFormatInfo: AudioFormatInfo?
    val audioDecoderInfo: AudioDecoderInfo?
    val replacementCandidateState: ReplacementCandidateState
    val downloadStates: Map<String, DownloadState>

    fun openFullPlayer()
    fun closeFullPlayer()
    fun toggleQueue()
    fun seekTo(positionMs: Long)
    fun toggleShuffle()
    fun toggleRepeat()

    fun clearQueue()
    fun playQueueIndex(index: Int)
    fun removeFromQueue(track: MusicTrack)
    fun playPlaybackPart(index: Int)

    fun setSleepTimerDurationMinutes(minutes: Int)
    fun clearSleepTimer()
    fun setSleepTimerToEndOfTrack()

    fun addToUpNext(track: MusicTrack)
    fun download(track: MusicTrack)
    fun deleteDownload(track: MusicTrack)
    fun openTrackArtist(track: MusicTrack)
    fun openTrackAlbum(track: MusicTrack)
    fun openOriginalTrackDetail(track: MusicTrack)
    fun openLocalMetadataEditor(track: MusicTrack)
    fun canAddTrackToPlaylist(track: MusicTrack): Boolean
    fun openPlaylistTargetPicker(track: MusicTrack)
    fun canRemoveTrackFromSelectedPlaylist(track: MusicTrack): Boolean
    fun removeTrackFromSelectedPlaylist(track: MusicTrack)
    fun canSetSongDisliked(track: MusicTrack): Boolean
    fun setSongDisliked(track: MusicTrack)

    fun loadReplacementCandidates(track: MusicTrack)
    fun selectReplacementCandidate(track: MusicTrack, candidate: ReplacementCandidate)
    fun openReplacementTrackDetail(track: MusicTrack)
}
