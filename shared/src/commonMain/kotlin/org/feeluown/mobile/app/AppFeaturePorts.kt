package org.feeluown.mobile

/**
 * App-shell capabilities that Search needs from sibling features.
 *
 * Implementations belong at platform composition roots while the legacy controller is being
 * decomposed. Search itself must only depend on this narrow port plus its feature owner.
 */
interface SearchAppPort {
    val providers: List<ProviderInfo>
    val downloadStates: Map<String, DownloadState>

    fun closeSearch()

    fun playResult(index: Int)

    fun addToUpNext(track: MusicTrack)

    fun download(track: MusicTrack)

    fun deleteDownload(track: MusicTrack)

    fun openArtist(track: MusicTrack)

    fun openAlbum(track: MusicTrack)

    fun openTrackDetail(track: MusicTrack)

    fun canAddToPlaylist(track: MusicTrack): Boolean

    fun openPlaylistTargetPicker(track: MusicTrack)

    fun openMediaItem(item: ProviderMediaItem)

    fun openPlaylist(playlist: ProviderPlaylist)

    fun openVideo(video: ProviderVideo)
}

/** App-shell capabilities that Recognition needs outside its feature owner. */
interface RecognitionAppPort {
    fun canOpenNeteaseDetail(song: RecognizedSong): Boolean

    fun openNeteaseDetail(song: RecognizedSong)
}
