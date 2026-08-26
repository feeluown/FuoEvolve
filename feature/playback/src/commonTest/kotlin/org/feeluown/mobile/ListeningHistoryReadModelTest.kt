package org.feeluown.mobile

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ListeningHistoryReadModelTest {
    @Test
    fun utcCalendarRangesHandleLeapYear() {
        val february = ListeningTimeRange.utcMonth(2024, 2)
        val year = ListeningTimeRange.utcYear(2024)

        assertEquals(29L * 86_400_000L, february.endExclusiveMillis - february.startInclusiveMillis)
        assertEquals(366L * 86_400_000L, year.endExclusiveMillis - year.startInclusiveMillis)
        assertEquals(year, ListeningTimeRange.utcYearContaining(1_704_067_200_000L))
    }

    @Test
    fun rollingRangeIncludesCurrentMillisecond() {
        val range = ListeningTimeRange.rollingDays(nowMillis = 1_000_000_000L, days = 7)

        assertEquals(1_000_000_001L, range.endExclusiveMillis)
        assertEquals((1_000_000_000L - 7L * 86_400_000L).coerceAtLeast(0L), range.startInclusiveMillis)
    }

    @Test
    fun personalizationUsesQualifiedRankingAndExplicitOverplayThreshold() = runTest {
        val repository = PersonalizationRepository(
            listOf(
                stat("a", qualified = 20L, playedMs = 200_000L),
                stat("b", qualified = 9L, playedMs = 500_000L),
                stat("c", qualified = 1L, playedMs = 50_000L),
            )
        )

        val result = repository.personalization(
            seedLimit = 2,
            overplayQualifiedThreshold = 10L,
            overplayLimit = 5,
        )

        assertEquals(listOf("a", "b"), result.recommendationSeeds.map { it.resource.sourceResourceId })
        assertEquals(listOf("a"), result.overplayedResources.map { it.resource.sourceResourceId })
    }

    private fun stat(id: String, qualified: Long, playedMs: Long) = ListeningResourceStat(
        resource = ListeningResourceSnapshot(
            resourceKey = "Track:7:netease:$id",
            type = ListeningResourceType.Track,
            sourceId = "netease",
            sourceResourceId = id,
            title = id,
        ),
        eventCount = qualified,
        qualifiedPlayCount = qualified,
        playedMs = playedMs,
        lastPlayedAtMillis = 1_000L,
    )
}

private class PersonalizationRepository(
    private val tracks: List<ListeningResourceStat>,
) : ListeningHistoryRepository {
    override suspend fun upsert(record: ListeningHistoryRecord) = Unit

    override suspend fun recentEvents(
        range: ListeningTimeRange,
        limit: Int,
        resourceType: ListeningResourceType?,
    ) = emptyList<ListeningHistoryEvent>()

    override suspend fun recentResources(
        range: ListeningTimeRange,
        limit: Int,
        resourceType: ListeningResourceType?,
    ) = emptyList<ListeningResourceStat>()

    override suspend fun topResources(
        resourceType: ListeningResourceType,
        range: ListeningTimeRange,
        limit: Int,
    ): List<ListeningResourceStat> = if (resourceType == ListeningResourceType.Track) tracks.take(limit) else emptyList()

    override suspend fun insights(range: ListeningTimeRange, trendBucketMs: Long) = ListeningInsights()

    override suspend fun clear() = Unit
}
