package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FileBackedLocalPlaylistRepositoryTest {
    @Test
    fun persistsCreateAndTrackMutationAcrossRepositoryInstances() = runTest {
        val storage = MemoryLocalPlaylistStorage()
        val first = FileBackedLocalPlaylistRepository(storage)

        val created = first.create("桌面歌单")
        assertTrue(created.success)
        val playlist = assertNotNull(created.playlist)
        val track = LocalPlaylistTrack(
            uri = "fuo://netease/songs/123",
            providerId = "netease",
            identifier = "123",
            title = "Song",
            artists = "Artist",
        )
        assertTrue(first.addTrack(playlist, track).success)

        val restored = FileBackedLocalPlaylistRepository(storage).list().single()
        assertEquals("桌面歌单", restored.title)
        assertEquals(listOf(track), restored.tracks)
    }

    @Test
    fun importUsesUniqueNamesAndExportReadsDurableContent() = runTest {
        val storage = MemoryLocalPlaylistStorage()
        val repository = FileBackedLocalPlaylistRepository(storage)
        val preview = LocalPlaylistImportPreview(
            fileName = "mix.fuo",
            title = "Mix",
            tracks = listOf(
                LocalPlaylistTrack(
                    uri = "fuo://qqmusic/songs/abc",
                    providerId = "qqmusic",
                    identifier = "abc",
                    title = "Track",
                ),
            ),
        )

        val first = assertNotNull(repository.importPlaylist(preview, LocalPlaylistImportMode.CreateNew).playlist)
        val second = assertNotNull(repository.importPlaylist(preview, LocalPlaylistImportMode.CreateNew).playlist)
        assertEquals("Mix.fuo", first.fileName)
        assertEquals("Mix_2.fuo", second.fileName)

        val exported = repository.export(first)
        val decoded = LocalPlaylistFileCodec.decode(exported.fileName, exported.content)
        assertEquals("Mix", decoded.title)
        assertEquals(1, decoded.tracks.size)
    }
}

private class MemoryLocalPlaylistStorage : LocalPlaylistFileStorage {
    private val files = linkedMapOf<String, String>()

    override suspend fun listFileNames(): List<String> = files.keys.toList()
    override suspend fun readText(fileName: String): String? = files[fileName]
    override suspend fun writeTextAtomically(fileName: String, content: String) {
        files[fileName] = content
    }
    override suspend fun delete(fileName: String): Boolean = files.remove(fileName) != null
    override suspend fun exists(fileName: String): Boolean = fileName in files
}
