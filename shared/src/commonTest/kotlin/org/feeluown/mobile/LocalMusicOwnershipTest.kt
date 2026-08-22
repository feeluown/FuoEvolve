package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LocalMusicOwnershipTest {
    @Test
    fun featureStateKeepsLocalMusicPresentationImmutable() {
        val track = localTrack("one", "One", "Music/")
        val state = LocalMusicUiState(
            tracks = listOf(track),
            viewMode = LocalMusicViewMode.Artist,
            excludedDirectoryIds = setOf("Podcasts/"),
            selectedCollection = LocalMusicCollectionSelection(LocalMusicViewMode.Artist, "Artist"),
            metadataEditorTrack = track,
        )

        assertEquals(listOf(track), state.tracks)
        assertEquals(LocalMusicViewMode.Artist, state.viewMode)
        assertEquals(setOf("Podcasts/"), state.excludedDirectoryIds)
        assertEquals(LocalMusicCollectionSelection(LocalMusicViewMode.Artist, "Artist"), state.selectedCollection)
        assertEquals(track, state.metadataEditorTrack)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
    }

    @Test
    fun localCollectionsRespectExcludedDirectories() {
        val tracks = listOf(
            localTrack("one", "One", "Music/"),
            localTrack("two", "Two", "Podcasts/"),
        )
        val directories = listOf(
            LocalMusicDirectory(id = "Music/", name = "Music", trackCount = 1),
            LocalMusicDirectory(id = "Podcasts/", name = "Podcasts", trackCount = 1),
        )

        val collections = buildLocalMusicCollections(
            mode = LocalMusicViewMode.All,
            tracks = tracks,
            directories = directories,
            excludedDirectoryIds = setOf("Podcasts"),
        )

        assertEquals(listOf("Music/"), collections.map { it.key })
        assertEquals(listOf("one"), collections.single().tracks.map { it.id })
    }

    private fun localTrack(id: String, title: String, directoryId: String) = MusicTrack(
        id = id,
        title = title,
        artists = "Artist",
        album = "Album",
        source = "local",
        sourceType = TrackSourceType.LocalMediaStore,
        localDirectoryId = directoryId,
    )
}
