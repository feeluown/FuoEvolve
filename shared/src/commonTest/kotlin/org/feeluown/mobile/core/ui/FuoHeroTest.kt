package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class FuoHeroTest {
    @Test
    fun duplicateResourceUsesActivatedSourceInstance() {
        val coordinator = ResourceHeroCoordinator()
        val identity = ResourceCoverHeroKey(
            type = ResourceCoverHeroType.Playlist,
            providerId = "netease",
            resourceId = "playlist-1",
        )
        val first = identity.forSource(scopeKey = "mine-frequent", occurrence = 0)
        val second = identity.forSource(scopeKey = "mine-playlists", occurrence = 0)

        coordinator.activate(first)
        assertEquals(first, coordinator.destinationKey(identity))

        coordinator.activate(second)
        assertEquals(second, coordinator.destinationKey(identity))
    }

    @Test
    fun sourceKeyIsStableForSameScopeAndOccurrence() {
        val identity = ResourceCoverHeroKey(
            type = ResourceCoverHeroType.Playlist,
            providerId = "netease",
            resourceId = "playlist-1",
        )

        assertEquals(
            identity.forSource(scopeKey = "stable-grid", occurrence = 0),
            identity.forSource(scopeKey = "stable-grid", occurrence = 0),
        )
        assertNotEquals(
            identity.forSource(scopeKey = "stable-grid", occurrence = 0),
            identity.forSource(scopeKey = "stable-grid", occurrence = 1),
        )
        assertNotEquals(
            identity.forSource(scopeKey = "first-grid", occurrence = 0),
            identity.forSource(scopeKey = "second-grid", occurrence = 0),
        )
    }

    @Test
    fun unrelatedActiveResourceDoesNotOverrideDestinationIdentity() {
        val coordinator = ResourceHeroCoordinator()
        val active = ResourceCoverHeroKey(
            type = ResourceCoverHeroType.Playlist,
            providerId = "netease",
            resourceId = "playlist-1",
            sourceInstanceId = "source",
        )
        val destination = ResourceCoverHeroKey(
            type = ResourceCoverHeroType.Playlist,
            providerId = "netease",
            resourceId = "playlist-2",
        )

        coordinator.activate(active)

        assertEquals(destination, coordinator.destinationKey(destination))
    }
}
