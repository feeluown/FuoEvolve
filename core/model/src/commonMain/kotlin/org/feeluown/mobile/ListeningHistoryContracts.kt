package org.feeluown.mobile

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
        trendBucketMs: Long = 86_400_000L,
    ): ListeningInsights

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
