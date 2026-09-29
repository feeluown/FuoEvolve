package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals

class HomeHistoryContentTest {
    @Test
    fun historyTrackPreservesProviderResourceIdentifier() {
        val resource = ListeningResourceSnapshot(
            resourceKey = "Track:7:ytmusic:ytmusic:video123",
            type = ListeningResourceType.Track,
            sourceId = "ytmusic",
            sourceResourceId = "ytmusic:video123",
            title = "Song",
            subtitle = "Artist",
        )

        val track = resource.toHomeHistoryTrack("YouTube Music")

        assertEquals("ytmusic:video123", track.id)
        assertEquals("ytmusic:video123", track.providerId)
        assertEquals("ytmusic", track.source)
        assertEquals("YouTube Music", track.providerName)
    }

    @Test
    fun eligibleHistoryIsFilteredBeforeShelfLimit() {
        val resources = buildList {
            repeat(12) { index ->
                add(stat(sourceId = "local", id = "local-$index"))
            }
            add(stat(sourceId = "netease", id = "enabled-1"))
            add(stat(sourceId = "qqmusic", id = "enabled-2"))
            add(stat(sourceId = "disabled", id = "disabled-1"))
        }

        val selected = selectHomeRecentTracks(
            resources = resources,
            enabledProviderIds = setOf("netease", "qqmusic"),
            limit = 12,
        )

        assertEquals(listOf("enabled-1", "enabled-2"), selected.map { it.resource.sourceResourceId })
    }

    private fun stat(sourceId: String, id: String) = ListeningResourceStat(
        resource = ListeningResourceSnapshot(
            resourceKey = "Track:${sourceId.length}:$sourceId:$id",
            type = ListeningResourceType.Track,
            sourceId = sourceId,
            sourceResourceId = id,
            title = id,
        ),
        eventCount = 1,
        qualifiedPlayCount = 1,
        playedMs = 1_000,
        lastPlayedAtMillis = 1,
    )
}
