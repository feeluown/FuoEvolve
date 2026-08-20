package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalMusicOwnershipTest {
    @Test
    fun compatibilityStatePublishesImmutableFeatureProjection() {
        val state = LocalMusicControllerState()
        var projection = LocalMusicUiState()
        var publishCount = 0
        state.observeChanges {
            projection = state.toUiState(projection)
            publishCount += 1
        }

        val track = MusicTrack(
            id = "local:1",
            title = "Song",
            artists = "Artist",
            album = "Album",
            source = "local",
            sourceType = TrackSourceType.LocalMediaStore,
            localDirectoryId = "Music/",
        )
        state.tracks = listOf(track)
        state.viewMode = LocalMusicViewMode.Artist
        state.excludedDirectoryIds = setOf("Podcasts/")
        state.selectedCollection = LocalMusicCollectionSelection(LocalMusicViewMode.Artist, "Artist")
        state.metadataEditorTrack = track

        assertEquals(listOf(track), projection.tracks)
        assertEquals(LocalMusicViewMode.Artist, projection.viewMode)
        assertEquals(setOf("Podcasts/"), projection.excludedDirectoryIds)
        assertEquals(LocalMusicCollectionSelection(LocalMusicViewMode.Artist, "Artist"), projection.selectedCollection)
        assertEquals(track, projection.metadataEditorTrack)
        assertEquals(6, publishCount)

        state.metadataEditorTrack = null
        assertNull(projection.metadataEditorTrack)
        assertEquals(7, publishCount)
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
