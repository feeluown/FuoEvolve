package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalPlaylistRepositoryTest {
    @Test
    fun supportsCreateAddDeduplicateRemoveExportAndDelete() = runTest {
        val repository = InMemoryLocalPlaylistRepository()
        val created = repository.create("我的收藏").playlist ?: error("创建歌单失败")
        val track = LocalPlaylistTrack(
            uri = "fuo://netease/songs/1",
            providerId = "netease",
            identifier = "1",
            title = "第一首",
        )
        assertTrue(repository.addTrack(created, track).success)
        val duplicate = repository.addTrack(created, track)
        assertFalse(duplicate.success)
        assertEquals(1, duplicate.playlist?.tracks?.size)

        val second = track.copy(
            uri = "fuo://netease/songs/2",
            identifier = "2",
            title = "第二首",
        )
        assertTrue(repository.addTrack(created, second).success)
        val current = repository.list().single()
        assertTrue(repository.removeTrack(current, track.uri).success)
        val updated = repository.list().single()
        assertEquals(listOf(second.uri), updated.tracks.map { it.uri })
        assertEquals(updated.fileName, repository.export(updated).fileName)
        assertTrue(repository.delete(updated).success)
        assertTrue(repository.list().isEmpty())
    }

    @Test
    fun importsByReplacingOrCreatingUniqueFile() = runTest {
        val repository = InMemoryLocalPlaylistRepository()
        val existing = repository.create("同名歌单").playlist!!
        val preview = LocalPlaylistImportPreview(
            fileName = "incoming.fuo",
            title = existing.title,
            description = "更新后的描述",
            tracks = listOf(
                LocalPlaylistTrack(
                    uri = "fuo://qqmusic/songs/9",
                    providerId = "qqmusic",
                    identifier = "9",
                ),
            ),
        )

        val replaced = repository.importPlaylist(preview, LocalPlaylistImportMode.Replace, existing)
        assertTrue(replaced.success)
        assertEquals(existing.fileName, replaced.playlist?.fileName)
        assertEquals("更新后的描述", repository.list().single().description)

        val created = repository.importPlaylist(preview, LocalPlaylistImportMode.CreateNew)
        assertTrue(created.success)
        assertEquals(2, repository.list().size)
        assertEquals(2, repository.list().map { it.fileName }.toSet().size)
    }
}
