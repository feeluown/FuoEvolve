package org.feeluown.mobile

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LocalPlaylistFeatureOwnerParityTest {
    @Test
    fun ownerLoadsExistingPlaylistsAtStartup() = runTest {
        val playlist = playlist()
        val owner = createLocalPlaylistFeatureController(
            repository = FakeLocalPlaylistRepository(initial = listOf(playlist)),
            navigator = AppNavigator(),
            scope = backgroundScope,
            providers = { emptyList() },
        )

        runCurrent()

        assertEquals(listOf(playlist), owner.uiState.value.playlists)
    }

    @Test
    fun addTrackWaitsForRepositoryFailureBeforeReportingResult() = runTest {
        val playlist = playlist()
        val owner = createLocalPlaylistFeatureController(
            repository = FakeLocalPlaylistRepository(
                initial = listOf(playlist),
                addResult = LocalPlaylistOperationResult(false, "写入本地歌单失败"),
            ),
            navigator = AppNavigator(),
            scope = backgroundScope,
            providers = { emptyList() },
        )
        runCurrent()

        val result = owner.addTrack(playlist, track())

        assertFalse(result.success)
        assertEquals("写入本地歌单失败", result.message)
        assertEquals("写入本地歌单失败", owner.uiState.value.operationError)
    }

    private fun playlist() = LocalPlaylist(
        id = "local:1",
        fileName = "playlist.fuo",
        title = "本地歌单",
    )

    private fun track() = MusicTrack(
        id = "netease:1",
        title = "Track",
        artists = "Artist",
        album = "Album",
        source = "netease",
        sourceType = TrackSourceType.Provider,
        providerId = "netease:1",
    )

    private class FakeLocalPlaylistRepository(
        private val initial: List<LocalPlaylist>,
        private val addResult: LocalPlaylistOperationResult = LocalPlaylistOperationResult(false, "unused"),
    ) : LocalPlaylistRepository {
        override suspend fun list(): List<LocalPlaylist> = initial

        override suspend fun create(title: String): LocalPlaylistOperationResult =
            LocalPlaylistOperationResult(false, "unused")

        override suspend fun delete(playlist: LocalPlaylist): LocalPlaylistOperationResult =
            LocalPlaylistOperationResult(false, "unused")

        override suspend fun addTrack(
            playlist: LocalPlaylist,
            track: LocalPlaylistTrack,
        ): LocalPlaylistOperationResult = addResult

        override suspend fun removeTrack(
            playlist: LocalPlaylist,
            uri: String,
        ): LocalPlaylistOperationResult = LocalPlaylistOperationResult(false, "unused")

        override suspend fun importPlaylist(
            preview: LocalPlaylistImportPreview,
            mode: LocalPlaylistImportMode,
            replacePlaylist: LocalPlaylist?,
        ): LocalPlaylistOperationResult = LocalPlaylistOperationResult(false, "unused")

        override suspend fun export(playlist: LocalPlaylist): LocalPlaylistFile =
            LocalPlaylistFile(playlist.fileName, "")
    }
}
