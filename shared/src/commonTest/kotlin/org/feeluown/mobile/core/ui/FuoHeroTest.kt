package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class FuoHeroTest {
    @Test
    fun duplicateResourceUsesActivatedSourceInstance() {
        val coordinator = ResourceHeroCoordinator()
        val identity = ResourceCoverHeroKey(
            type = ResourceCoverHeroType.Playlist,
            providerId = "netease",
            resourceId = "playlist-1",
        )
        val first = identity.copy(sourceInstanceId = "first")
        val second = identity.copy(sourceInstanceId = "second")

        coordinator.activate(first)
        assertEquals(first, coordinator.destinationKey(identity))

        coordinator.activate(second)
        assertEquals(second, coordinator.destinationKey(identity))
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
