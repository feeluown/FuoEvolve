package org.feeluown.mobile

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

    /** Replace the active source queue and start the selected item through the queue owner. */
    fun playTracks(tracks: List<MusicTrack>, index: Int)
    fun toggleShuffle()
    fun toggleRepeat()
    fun clearQueue()
    fun playQueueIndex(index: Int)
    fun removeFromQueue(track: MusicTrack)
    fun playPlaybackPart(index: Int)
    fun addToUpNext(track: MusicTrack)
}

/** Sleep-timer state and commands owned by playback. */
interface PlaybackSleepTimerPort {
    val sleepTimerState: SleepTimerState

    fun setSleepTimerDurationMinutes(minutes: Int)
    fun clearSleepTimer()
    fun setSleepTimerToEndOfTrack()
}

/** Download state/actions used by the now-playing surface. */
interface DownloadActionPort {
    val downloadStates: Map<String, DownloadState>

    fun download(track: MusicTrack)
    fun deleteDownload(track: MusicTrack)
}

/** Playlist mutations used by the now-playing surface. */
interface PlaylistActionPort {
    fun canAddTrackToPlaylist(track: MusicTrack): Boolean
    fun openPlaylistTargetPicker(track: MusicTrack)
    fun canRemoveTrackFromSelectedPlaylist(track: MusicTrack): Boolean
    fun removeTrackFromSelectedPlaylist(track: MusicTrack)
}

/** Provider-backed track navigation and dislike actions. */
interface ProviderTrackActionPort {
    fun openTrackArtist(track: MusicTrack)
    fun openTrackAlbum(track: MusicTrack)
    fun openOriginalTrackDetail(track: MusicTrack)
    fun canSetSongDisliked(track: MusicTrack): Boolean
    fun setSongDisliked(track: MusicTrack)
}

/** Local-library actions surfaced from now playing. */
interface LocalMusicActionPort {
    fun openLocalMetadataEditor(track: MusicTrack)
}

/** Smart-replacement state/actions used by the now-playing surface. */
interface ReplacementActionPort {
    val replacementCandidateState: ReplacementCandidateState

    fun loadReplacementCandidates(track: MusicTrack)
    fun selectReplacementCandidate(track: MusicTrack, candidate: ReplacementCandidate)
    fun openReplacementTrackDetail(track: MusicTrack)
}
