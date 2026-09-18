package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaylistMigrationTargetCapabilityTest {
    @Test
    fun creationRequiresPlaylistCreationAndTrackWriting() {
        assertFalse(ProviderCapabilities("target", "目标平台").canCreateMigrationDestination())
        assertFalse(
            ProviderCapabilities("target", "目标平台", canAddSongToPlaylist = true)
                .canCreateMigrationDestination(),
        )
        assertFalse(
            ProviderCapabilities("target", "目标平台", canCreatePlaylist = true)
                .canCreateMigrationDestination(),
        )
        assertTrue(
            ProviderCapabilities(
                "target",
                "目标平台",
                canAddSongToPlaylist = true,
                canCreatePlaylist = true,
            ).canCreateMigrationDestination(),
        )
    }
}
