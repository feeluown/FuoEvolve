package org.feeluown.mobile.feature.localplaylist

import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LocalPlaylistFeatureTest {
    @Test
    fun loadsAndAddsTracksThroughFeatureOperations() = runTest {
        val operations = FakeOperations()
        val owner = createLocalPlaylistFeatureOwner(
            operations = operations,
            scope = this,
            openPlaylist = {},
            closePlaylist = {},
        )

        assertEquals(listOf(Playlist("1", "One")), owner.loadForContent())
        assertEquals(1, owner.uiState.value.playlists.size)

        val result = owner.addTrack(Playlist("1", "One"), Track("track"))
        advanceUntilIdle()

        assertFalse(result.success.not())
        assertEquals("added", result.message)
        assertEquals(listOf(Track("track")), owner.uiState.value.playlists.single().tracks)
    }

    private data class Track(val id: String)
    private data class Playlist(val id: String, val title: String, val tracks: List<Track> = emptyList())
    private data class Preview(val title: String)
    private data class Export(val content: String)
    private data class Result(val success: Boolean, val message: String, val playlist: Playlist? = null)
    private enum class Mode { Replace, Create }

    private class FakeOperations : LocalPlaylistFeatureOperations<Track, Playlist, Preview, Mode, Export, Result> {
        private var playlists = listOf(Playlist("1", "One"))
        override suspend fun list(): List<Playlist> = playlists
        override suspend fun create(title: String) = Result(true, "created", Playlist("2", title))
        override suspend fun delete(playlist: Playlist) = Result(true, "deleted")
        override suspend fun addTrack(playlist: Playlist, track: Track): Result {
            val updated = playlist.copy(tracks = playlist.tracks + track)
            playlists = playlists.map { if (it.id == updated.id) updated else it }
            return Result(true, "added", updated)
        }
        override suspend fun removeTrack(playlist: Playlist, track: Track) =
            Result(true, "removed", playlist.copy(tracks = playlist.tracks - track))
        override suspend fun importPlaylist(preview: Preview, mode: Mode, replacePlaylist: Playlist?) = Result(true, "imported")
        override suspend fun export(playlist: Playlist) = Export(playlist.title)
        override fun decode(fileName: String, content: String) = Preview(content)
        override fun tracks(playlist: Playlist) = playlist.tracks
        override fun canAddTrack(track: Track) = true
        override fun containsTrack(playlist: Playlist, track: Track) = track in playlist.tracks
        override fun removeTrackLocally(playlist: Playlist, track: Track) = playlist.copy(tracks = playlist.tracks - track)
        override fun playlistId(playlist: Playlist) = playlist.id
        override fun playlistTitle(playlist: Playlist) = playlist.title
        override fun previewTitle(preview: Preview) = preview.title
        override fun previewTrackCount(preview: Preview) = 0
        override fun previewSkippedLineCount(preview: Preview) = 0
        override fun resultSuccess(result: Result) = result.success
        override fun resultMessage(result: Result) = result.message
        override fun resultPlaylist(result: Result) = result.playlist
        override fun withResultMessage(result: Result, message: String) = result.copy(message = message)
        override fun failureResult(message: String) = Result(false, message)
    }
}
