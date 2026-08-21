package org.feeluown.mobile

import androidx.compose.runtime.Composable

/**
 * Temporary P2 bridge while AppRoot/Home still obtain Local Playlist state from the compatibility
 * facade. The actual Local Playlist screen is controller-free; this file is deleted when the
 * composition root starts owning [LocalPlaylistFeatureController].
 */
@Composable
fun LocalPlaylistScreen(
    controller: FuoPlayerController,
    playlist: LocalPlaylist?,
) {
    LocalPlaylistScreen(
        uiState = LocalPlaylistUiState(
            playlists = controller.localPlaylists,
            selectedPlaylist = controller.selectedLocalPlaylist,
            selectedTracks = controller.selectedLocalPlaylistTracks,
            selectedError = controller.selectedLocalPlaylistError,
            operationError = controller.localPlaylistOperationError,
            importPreview = controller.localPlaylistImportPreview,
            isLoading = controller.isLoading,
            message = controller.message,
        ),
        actions = LegacyLocalPlaylistUiActions(controller),
        playlist = playlist,
    )
}

private class LegacyLocalPlaylistUiActions(
    private val controller: FuoPlayerController,
) : LocalPlaylistUiActions {
    override fun refresh() = controller.refreshLocalPlaylists()
    override fun create(title: String) = controller.createLocalPlaylist(title)
    override fun open(playlist: LocalPlaylist) = controller.openLocalPlaylist(playlist)
    override fun close() = controller.closeLocalPlaylist()
    override fun canRemove(track: MusicTrack): Boolean = controller.canRemoveTrackFromSelectedLocalPlaylist(track)
    override fun remove(track: MusicTrack) = controller.removeTrackFromSelectedLocalPlaylist(track)
    override fun canDeleteSelected(): Boolean = controller.canDeleteSelectedLocalPlaylist()
    override fun deleteSelected() = controller.deleteSelectedLocalPlaylist()
    override fun prepareImport(fileName: String, content: String) =
        controller.prepareLocalPlaylistImport(fileName, content)
    override fun existingForImport(preview: LocalPlaylistImportPreview): LocalPlaylist? =
        controller.existingLocalPlaylistForImport(preview)
    override fun cancelImport() = controller.cancelLocalPlaylistImport()
    override fun importPlaylist(mode: LocalPlaylistImportMode, replacePlaylistId: String?) =
        controller.importLocalPlaylist(mode, replacePlaylistId)
    override fun exportSelected(onReady: (LocalPlaylistFile) -> Unit) =
        controller.exportSelectedLocalPlaylist(onReady)
}
