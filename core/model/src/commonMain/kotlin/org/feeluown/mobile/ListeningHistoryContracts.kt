package org.feeluown.mobile

private const val LISTENING_DAY_MILLIS = 86_400_000L

enum class ListeningResourceType {
    Track,
    Video,
    Artist,
    Album,
    Playlist,
    Feature,
    Search,
    LocalDirectory,
    Episode,
    Podcast,
}

enum class ListeningResourceRelationType {
    Primary,
    Artist,
    Album,
    PlaylistContext,
    FeatureContext,
    SearchContext,
    LocalDirectory,
    ResolvedSource,
}

enum class ListeningStartReason {
    UserSelection,
    PlaylistReplace,
    AutoNext,
    Resume,
    RestoreSession,
    Unknown,
}

enum class ListeningCompletionReason {
    Ended,
    Changed,
    Stopped,
    Error,
}

data class ListeningResourceSnapshot(
    val resourceKey: String,
    val type: ListeningResourceType,
    val sourceId: String,
    val sourceResourceId: String,
    val title: String,
    val subtitle: String = "",
    val coverUrl: String? = null,
    val metadataJson: String? = null,
)

data class ListeningResourceRelation(
    val resource: ListeningResourceSnapshot,
    val relation: ListeningResourceRelationType,
)

data class ListeningHistoryRecord(
    val sessionKey: String,
    val primaryResourceKey: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val playedMs: Long = 0L,
    val durationMs: Long? = null,
    val qualified: Boolean = false,
    val startReason: ListeningStartReason = ListeningStartReason.Unknown,
    val completionReason: ListeningCompletionReason? = null,
    val contextSessionKey: String? = null,
    val resources: List<ListeningResourceRelation>,
    val updatedAtMillis: Long,
)

/** Inclusive start / exclusive end range used by every listening-history read model. */
data class ListeningTimeRange(
    val startInclusiveMillis: Long = 0L,
    val endExclusiveMillis: Long = Long.MAX_VALUE,
) {
    init {
        require(startInclusiveMillis >= 0L) { "startInclusiveMillis must be non-negative" }
        require(endExclusiveMillis > startInclusiveMillis) { "endExclusiveMillis must be after startInclusiveMillis" }
    }

    companion object {
        val All = ListeningTimeRange()

        fun rollingDays(nowMillis: Long, days: Int): ListeningTimeRange {
            require(nowMillis >= 0L) { "nowMillis must be non-negative" }
            require(days > 0) { "days must be positive" }
            val duration = days.toLong().coerceAtMost(Long.MAX_VALUE / LISTENING_DAY_MILLIS) * LISTENING_DAY_MILLIS
            val start = (nowMillis - duration).coerceAtLeast(0L)
            val end = if (nowMillis == Long.MAX_VALUE) Long.MAX_VALUE else nowMillis + 1L
            return ListeningTimeRange(start, end)
        }

        fun utcYear(year: Int): ListeningTimeRange {
            require(year >= 1970) { "year must be 1970 or later" }
            return ListeningTimeRange(
                startInclusiveMillis = listeningDaysFromCivil(year, 1, 1) * LISTENING_DAY_MILLIS,
                endExclusiveMillis = listeningDaysFromCivil(year + 1, 1, 1) * LISTENING_DAY_MILLIS,
            )
        }

        fun utcMonth(year: Int, month: Int): ListeningTimeRange {
            require(year >= 1970) { "year must be 1970 or later" }
            require(month in 1..12) { "month must be in 1..12" }
            val nextYear = if (month == 12) year + 1 else year
            val nextMonth = if (month == 12) 1 else month + 1
            return ListeningTimeRange(
                startInclusiveMillis = listeningDaysFromCivil(year, month, 1) * LISTENING_DAY_MILLIS,
                endExclusiveMillis = listeningDaysFromCivil(nextYear, nextMonth, 1) * LISTENING_DAY_MILLIS,
            )
        }

        fun utcYearContaining(timestampMillis: Long): ListeningTimeRange {
            require(timestampMillis >= 0L) { "timestampMillis must be non-negative" }
            val year = listeningCivilFromEpochDay(timestampMillis / LISTENING_DAY_MILLIS).first
            return utcYear(year)
        }
    }
}

data class ListeningHistoryEvent(
    val sessionKey: String,
    val primaryResource: ListeningResourceSnapshot,
    val startedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val playedMs: Long,
    val durationMs: Long? = null,
    val qualified: Boolean,
    val startReason: ListeningStartReason,
    val completionReason: ListeningCompletionReason? = null,
    val contextSessionKey: String? = null,
)

data class ListeningResourceStat(
    val resource: ListeningResourceSnapshot,
    val eventCount: Long,
    val qualifiedPlayCount: Long,
    val playedMs: Long,
    val lastPlayedAtMillis: Long,
    /** Non-zero for context resources such as playlists/features when context sessions are recorded. */
    val contextSessionCount: Long = 0L,
)

data class ListeningSourceShare(
    val sourceId: String,
    val eventCount: Long,
    val playedMs: Long,
)

data class ListeningTrendPoint(
    val bucketStartMillis: Long,
    val eventCount: Long,
    val qualifiedPlayCount: Long,
    val playedMs: Long,
)

data class ListeningInsights(
    val eventCount: Long = 0L,
    val qualifiedPlayCount: Long = 0L,
    val totalPlayedMs: Long = 0L,
    val activeDays: Long = 0L,
    val userSelectedPlayCount: Long = 0L,
    val automaticPlayCount: Long = 0L,
    val sourceShares: List<ListeningSourceShare> = emptyList(),
    val trend: List<ListeningTrendPoint> = emptyList(),
)

data class ListeningPersonalization(
    val recommendationSeeds: List<ListeningResourceStat> = emptyList(),
    val overplayedResources: List<ListeningResourceStat> = emptyList(),
)

interface ListeningHistorySink {
    suspend fun upsert(record: ListeningHistoryRecord)
}

/**
 * Provider-neutral read/write history contract.
 *
 * The event table remains the source of truth. Recent resources, frequent resources and insights are
 * projections so product ranking can evolve without rewriting raw history.
 */
interface ListeningHistoryRepository : ListeningHistorySink {
    suspend fun recentEvents(
        range: ListeningTimeRange = ListeningTimeRange.All,
        limit: Int = 200,
        resourceType: ListeningResourceType? = null,
    ): List<ListeningHistoryEvent>

    suspend fun recentResources(
        range: ListeningTimeRange = ListeningTimeRange.All,
        limit: Int = 50,
        resourceType: ListeningResourceType? = null,
    ): List<ListeningResourceStat>

    suspend fun topResources(
        resourceType: ListeningResourceType,
        range: ListeningTimeRange = ListeningTimeRange.All,
        limit: Int = 50,
    ): List<ListeningResourceStat>

    suspend fun insights(
        range: ListeningTimeRange = ListeningTimeRange.All,
        trendBucketMs: Long = LISTENING_DAY_MILLIS,
    ): ListeningInsights

    suspend fun personalization(
        range: ListeningTimeRange = ListeningTimeRange.All,
        seedLimit: Int = 20,
        overplayQualifiedThreshold: Long = 10L,
        overplayLimit: Int = 20,
    ): ListeningPersonalization {
        require(seedLimit >= 0) { "seedLimit must be non-negative" }
        require(overplayQualifiedThreshold > 0L) { "overplayQualifiedThreshold must be positive" }
        require(overplayLimit >= 0) { "overplayLimit must be non-negative" }
        val queryLimit = maxOf(seedLimit, overplayLimit, 1) * 3
        val tracks = topResources(ListeningResourceType.Track, range, queryLimit)
            .filter { it.qualifiedPlayCount > 0L }
        return ListeningPersonalization(
            recommendationSeeds = tracks.take(seedLimit),
            overplayedResources = tracks.filter { it.qualifiedPlayCount >= overplayQualifiedThreshold }.take(overplayLimit),
        )
    }

    suspend fun clear()
}

object NoOpListeningHistorySink : ListeningHistorySink {
    override suspend fun upsert(record: ListeningHistoryRecord) = Unit
}

object NoOpListeningHistoryRepository : ListeningHistoryRepository {
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
    ) = emptyList<ListeningResourceStat>()
    override suspend fun insights(range: ListeningTimeRange, trendBucketMs: Long) = ListeningInsights()
    override suspend fun clear() = Unit
}

private fun listeningCivilFromEpochDay(epochDay: Long): Triple<Int, Int, Int> {
    val z = epochDay + 719_468L
    val era = z / 146_097L
    val dayOfEra = z - era * 146_097L
    val yearOfEra = (dayOfEra - dayOfEra / 1_460L + dayOfEra / 36_524L - dayOfEra / 146_096L) / 365L
    var year = (yearOfEra + era * 400L).toInt()
    val dayOfYear = dayOfEra - (365L * yearOfEra + yearOfEra / 4L - yearOfEra / 100L)
    val monthPrime = (5L * dayOfYear + 2L) / 153L
    val day = (dayOfYear - (153L * monthPrime + 2L) / 5L + 1L).toInt()
    val month = (monthPrime + if (monthPrime < 10L) 3L else -9L).toInt()
    if (month <= 2) year += 1
    return Triple(year, month, day)
}

private fun listeningDaysFromCivil(yearValue: Int, monthValue: Int, dayValue: Int): Long {
    var year = yearValue.toLong()
    val month = monthValue.toLong()
    if (month <= 2L) year -= 1L
    val era = year / 400L
    val yearOfEra = year - era * 400L
    val monthPrime = month + if (month > 2L) -3L else 9L
    val dayOfYear = (153L * monthPrime + 2L) / 5L + dayValue - 1L
    val dayOfEra = yearOfEra * 365L + yearOfEra / 4L - yearOfEra / 100L + dayOfYear
    return era * 146_097L + dayOfEra - 719_468L
}
