package org.feeluown.mobile

/** App-shell compatibility adapter while playback presentation ownership is extracted. */
internal class ControllerPlaybackUiPort(
    private val controller: FuoPlayerController,
) : PlaybackUiPort {
    override val isFullPlayerOpen: Boolean get() = controller.isFullPlayerOpen
    override val isQueueOpen: Boolean get() = controller.isQueueOpen
    override val trackChangeDirection: TrackChangeDirection get() = controller.trackChangeDirection
    override val currentTrack: MusicTrack? get() = controller.playbackState.currentTrack
    override val queue: List<MusicTrack> get() = controller.playbackState.queue
    override val playbackParts: List<PlaybackPart> get() = controller.playbackState.playbackParts
    override val currentPartIndex: Int get() = controller.playbackState.currentPartIndex
    override val displayUpNextCount: Int get() = controller.displayUpNextCount
    override val isShuffleEnabled: Boolean get() = controller.isShuffleEnabled
    override val repeatMode: RepeatMode get() = controller.repeatMode
    override val isFmQueueActive: Boolean get() = controller.isFmQueueActive
    override val sleepTimerState: SleepTimerState get() = controller.sleepTimerState
    override val lyricFontSize: LyricFontSize get() = controller.lyricFontSize
    override val themeMode: ThemeMode get() = controller.themeMode
    override val dynamicCoverColorEnabled: Boolean get() = controller.dynamicCoverColorEnabled
    override val audioQuality: String? get() = controller.playbackState.audioQuality
    override val audioFormatInfo: AudioFormatInfo? get() = controller.playbackState.audioFormatInfo
    override val audioDecoderInfo: AudioDecoderInfo? get() = controller.playbackState.audioDecoderInfo
    override val replacementCandidateState: ReplacementCandidateState get() = controller.replacementCandidateState
    override val downloadStates: Map<String, DownloadState> get() = controller.downloadStates

    override fun openFullPlayer() = controller.openFullPlayer()
    override fun closeFullPlayer() = controller.closeFullPlayer()
    override fun toggleQueue() = controller.toggleQueue()
    override fun seekTo(positionMs: Long) = controller.seekTo(positionMs)
    override fun toggleShuffle() = controller.toggleShuffle()
    override fun toggleRepeat() = controller.toggleRepeat()

    override fun clearQueue() = controller.clearQueue()
    override fun playQueueIndex(index: Int) = controller.playQueueIndex(index)
    override fun removeFromQueue(track: MusicTrack) = controller.removeFromQueue(track)
    override fun playPlaybackPart(index: Int) = controller.playPlaybackPart(index)

    override fun setSleepTimerDurationMinutes(minutes: Int) = controller.setSleepTimerDurationMinutes(minutes)
    override fun clearSleepTimer() = controller.clearSleepTimer()
    override fun setSleepTimerToEndOfTrack() = controller.setSleepTimerToEndOfTrack()

    override fun addToUpNext(track: MusicTrack) = controller.addToUpNext(track)
    override fun download(track: MusicTrack) = controller.download(track)
    override fun deleteDownload(track: MusicTrack) = controller.deleteDownload(track)
    override fun openTrackArtist(track: MusicTrack) = controller.openTrackArtist(track)
    override fun openTrackAlbum(track: MusicTrack) = controller.openTrackAlbum(track)
    override fun openOriginalTrackDetail(track: MusicTrack) = controller.openOriginalTrackDetail(track)
    override fun openLocalMetadataEditor(track: MusicTrack) = controller.openLocalMetadataEditor(track)
    override fun canAddTrackToPlaylist(track: MusicTrack): Boolean = controller.canAddTrackToPlaylist(track)
    override fun openPlaylistTargetPicker(track: MusicTrack) = controller.openPlaylistTargetPicker(track)
    override fun canRemoveTrackFromSelectedPlaylist(track: MusicTrack): Boolean =
        controller.canRemoveTrackFromSelectedPlaylist(track)
    override fun removeTrackFromSelectedPlaylist(track: MusicTrack) = controller.removeTrackFromSelectedPlaylist(track)
    override fun canSetSongDisliked(track: MusicTrack): Boolean = controller.canSetSongDisliked(track, true)
    override fun setSongDisliked(track: MusicTrack) = controller.setSongDisliked(track, true)

    override fun loadReplacementCandidates(track: MusicTrack) = controller.loadReplacementCandidates(track)
    override fun selectReplacementCandidate(track: MusicTrack, candidate: ReplacementCandidate) =
        controller.selectReplacementCandidate(track, candidate)
    override fun openReplacementTrackDetail(track: MusicTrack) = controller.openReplacementTrackDetail(track)
}
