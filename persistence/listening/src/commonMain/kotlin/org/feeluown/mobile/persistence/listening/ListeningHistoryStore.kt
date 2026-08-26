package org.feeluown.mobile.persistence.listening

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.feeluown.mobile.ListeningCompletionReason
import org.feeluown.mobile.ListeningHistoryEvent
import org.feeluown.mobile.ListeningHistoryRecord
import org.feeluown.mobile.ListeningHistoryRepository
import org.feeluown.mobile.ListeningInsights
import org.feeluown.mobile.ListeningLegacyPlaylistStat
import org.feeluown.mobile.ListeningResourceRelationType
import org.feeluown.mobile.ListeningResourceSnapshot
import org.feeluown.mobile.ListeningResourceStat
import org.feeluown.mobile.ListeningResourceType
import org.feeluown.mobile.ListeningSourceShare
import org.feeluown.mobile.ListeningStartReason
import org.feeluown.mobile.ListeningTimeRange
import org.feeluown.mobile.ListeningTrendPoint
import org.feeluown.mobile.persistence.listening.db.ListeningHistoryDatabase

private const val LEGACY_PLAYLIST_MIGRATION_KEY = "legacy_playlist_stats_v1"
private const val MIGRATION_COMPLETE = "complete"
private const val LEGACY_PLAYLIST_QUERY_FLOOR = 500
private const val FALLBACK_PLAYLIST_SOURCE = "context"

interface ListeningHistoryDriverFactory {
    fun createDriver(): SqlDriver
}

class SqlDelightListeningHistoryStore(
    driverFactory: ListeningHistoryDriverFactory,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ListeningHistoryRepository {
    private val database = ListeningHistoryDatabase(driverFactory.createDriver())
    private val mutex = Mutex()

    override suspend fun upsert(record: ListeningHistoryRecord) {
        withContext(dispatcher) {
            mutex.withLock {
                database.transaction {
                    val queries = database.listeningHistoryQueries
                    record.resources
                        .map { it.resource }
                        .distinctBy { it.resourceKey }
                        .forEach { resource ->
                            queries.upsertResource(
                                resourceKey = resource.resourceKey,
                                resourceType = resource.type.name,
                                sourceId = resource.sourceId,
                                sourceResourceId = resource.sourceResourceId,
                                title = resource.title,
                                subtitle = resource.subtitle,
                                coverUrl = resource.coverUrl,
                                metadataJson = resource.metadataJson,
                                updatedAt = record.updatedAtMillis,
                            )
                        }
                    queries.upsertEvent(
                        sessionKey = record.sessionKey,
                        primaryResourceKey = record.primaryResourceKey,
                        startedAt = record.startedAtMillis,
                        endedAt = record.endedAtMillis,
                        playedMs = record.playedMs,
                        durationMs = record.durationMs,
                        qualified = if (record.qualified) 1L else 0L,
                        startReason = record.startReason.name,
                        completionReason = record.completionReason?.name,
                        contextSessionKey = record.contextSessionKey,
                        updatedAt = record.updatedAtMillis,
                    )
                    queries.deleteEventRelations(record.sessionKey)
                    record.resources
                        .distinctBy { it.relation to it.resource.resourceKey }
                        .forEach { relation ->
                            queries.insertEventRelation(
                                sessionKey = record.sessionKey,
                                resourceKey = relation.resource.resourceKey,
                                relationType = relation.relation.name,
                            )
                        }
                }
            }
        }
    }

    override suspend fun migrateLegacyPlaylistStats(stats: List<ListeningLegacyPlaylistStat>) {
        withContext(dispatcher) {
            mutex.withLock {
                val queries = database.listeningHistoryQueries
                if (queries.selectMetadataValue(LEGACY_PLAYLIST_MIGRATION_KEY).executeAsOneOrNull() == MIGRATION_COMPLETE) {
                    return@withLock
                }
                database.transaction {
                    stats.asSequence()
                        .filter { stat ->
                            stat.sourceId.isNotBlank() &&
                                stat.sourceResourceId.isNotBlank() &&
                                stat.playCount > 0L &&
                                stat.lastPlayedAtMillis > 0L
                        }
                        .distinctBy { it.sourceId to it.sourceResourceId }
                        .forEach { stat ->
                            // Old and new counters co-existed in preview builds. Subtract context sessions already
                            // represented by real events so migration preserves only the missing historical baseline.
                            val representedSessions = queries.selectExistingPlaylistContextSessionCount(
                                sourceResourceId = stat.sourceResourceId,
                                sourceId = stat.sourceId,
                            ).executeAsOne()
                            queries.upsertLegacyPlaylistStat(
                                sourceId = stat.sourceId,
                                sourceResourceId = stat.sourceResourceId,
                                playCount = (stat.playCount - representedSessions).coerceAtLeast(0L),
                                lastPlayedAt = stat.lastPlayedAtMillis,
                            )
                        }
                    queries.upsertMetadata(LEGACY_PLAYLIST_MIGRATION_KEY, MIGRATION_COMPLETE)
                }
            }
        }
    }

    override suspend fun recentEvents(
        range: ListeningTimeRange,
        limit: Int,
        resourceType: ListeningResourceType?,
    ): List<ListeningHistoryEvent> = withContext(dispatcher) {
        mutex.withLock {
            database.listeningHistoryQueries.selectRecentEvents(
                startAt = range.startInclusiveMillis,
                endAt = range.endExclusiveMillis,
                resourceType = resourceType?.name.orEmpty(),
                limit = limit.coerceAtLeast(0).toLong(),
                mapper = { sessionKey, startedAt, endedAt, playedMs, durationMs, qualified, startReason,
                    completionReason, contextSessionKey, resourceKey, storedResourceType, sourceId,
                    sourceResourceId, title, subtitle, coverUrl, metadataJson ->
                    ListeningHistoryEvent(
                        sessionKey = sessionKey,
                        primaryResource = resourceSnapshot(
                            resourceKey = resourceKey,
                            resourceType = storedResourceType,
                            sourceId = sourceId,
                            sourceResourceId = sourceResourceId,
                            title = title,
                            subtitle = subtitle,
                            coverUrl = coverUrl,
                            metadataJson = metadataJson,
                        ),
                        startedAtMillis = startedAt,
                        endedAtMillis = endedAt,
                        playedMs = playedMs,
                        durationMs = durationMs,
                        qualified = qualified != 0L,
                        startReason = listeningStartReason(startReason),
                        completionReason = completionReason?.let(::listeningCompletionReason),
                        contextSessionKey = contextSessionKey,
                    )
                },
            ).executeAsList()
        }
    }

    override suspend fun recentResources(
        range: ListeningTimeRange,
        limit: Int,
        resourceType: ListeningResourceType?,
    ): List<ListeningResourceStat> = withContext(dispatcher) {
        mutex.withLock {
            val queries = database.listeningHistoryQueries
            if (resourceType == null || resourceType == ListeningResourceType.Track ||
                resourceType == ListeningResourceType.Video || resourceType == ListeningResourceType.Episode
            ) {
                queries.selectRecentResources(
                    startAt = range.startInclusiveMillis,
                    endAt = range.endExclusiveMillis,
                    resourceType = resourceType?.name.orEmpty(),
                    limit = limit.coerceAtLeast(0).toLong(),
                    mapper = ::resourceStat,
                ).executeAsList()
            } else {
                queries.selectRecentRelatedResources(
                    startAt = range.startInclusiveMillis,
                    endAt = range.endExclusiveMillis,
                    resourceType = resourceType.name,
                    relationType = relationFor(resourceType).name,
                    limit = limit.coerceAtLeast(0).toLong(),
                    mapper = ::resourceStat,
                ).executeAsList()
            }
        }
    }

    override suspend fun topResources(
        resourceType: ListeningResourceType,
        range: ListeningTimeRange,
        limit: Int,
    ): List<ListeningResourceStat> = withContext(dispatcher) {
        mutex.withLock {
            val safeLimit = limit.coerceAtLeast(0)
            if (safeLimit == 0) return@withLock emptyList()
            val queries = database.listeningHistoryQueries
            val queryLimit = if (resourceType == ListeningResourceType.Playlist && range == ListeningTimeRange.All) {
                maxOf(safeLimit * 2, LEGACY_PLAYLIST_QUERY_FLOOR)
            } else {
                safeLimit
            }
            val actual = queries.selectResourceStats(
                startAt = range.startInclusiveMillis,
                endAt = range.endExclusiveMillis,
                resourceType = resourceType.name,
                relationType = relationFor(resourceType).name,
                limit = queryLimit.toLong(),
                mapper = ::resourceStat,
            ).executeAsList()
            if (resourceType != ListeningResourceType.Playlist || range != ListeningTimeRange.All) {
                return@withLock actual.take(safeLimit)
            }
            val legacy = queries.selectLegacyPlaylistStats { sourceId, sourceResourceId, playCount, lastPlayedAt ->
                LegacyPlaylistBaseline(sourceId, sourceResourceId, playCount, lastPlayedAt)
            }.executeAsList()
            mergePlaylistBaselines(actual, legacy, safeLimit)
        }
    }

    override suspend fun insights(
        range: ListeningTimeRange,
        trendBucketMs: Long,
    ): ListeningInsights = withContext(dispatcher) {
        require(trendBucketMs > 0L) { "trendBucketMs must be positive" }
        mutex.withLock {
            val queries = database.listeningHistoryQueries
            val summary = queries.selectSummary(
                startAt = range.startInclusiveMillis,
                endAt = range.endExclusiveMillis,
                mapper = { eventCount, qualifiedPlayCount, totalPlayedMs, activeDays, userSelectedPlayCount, automaticPlayCount ->
                    ListeningInsights(
                        eventCount = eventCount,
                        qualifiedPlayCount = qualifiedPlayCount,
                        totalPlayedMs = totalPlayedMs,
                        activeDays = activeDays,
                        userSelectedPlayCount = userSelectedPlayCount,
                        automaticPlayCount = automaticPlayCount,
                    )
                },
            ).executeAsOne()
            val sourceShares = queries.selectSourceShares(
                startAt = range.startInclusiveMillis,
                endAt = range.endExclusiveMillis,
                mapper = { sourceId, eventCount, totalPlayedMs ->
                    ListeningSourceShare(sourceId = sourceId, eventCount = eventCount, playedMs = totalPlayedMs)
                },
            ).executeAsList()
            val trend = queries.selectTrend(
                bucketMs = trendBucketMs,
                startAt = range.startInclusiveMillis,
                endAt = range.endExclusiveMillis,
                mapper = { bucketStart, eventCount, qualifiedPlayCount, totalPlayedMs ->
                    ListeningTrendPoint(
                        bucketStartMillis = bucketStart,
                        eventCount = eventCount,
                        qualifiedPlayCount = qualifiedPlayCount,
                        playedMs = totalPlayedMs,
                    )
                },
            ).executeAsList()
            summary.copy(sourceShares = sourceShares, trend = trend)
        }
    }

    suspend fun eventCount(): Long = withContext(dispatcher) {
        mutex.withLock { database.listeningHistoryQueries.countEvents().executeAsOne() }
    }

    override suspend fun clear() {
        withContext(dispatcher) {
            mutex.withLock {
                database.transaction {
                    val queries = database.listeningHistoryQueries
                    queries.clearEventRelations()
                    queries.clearEvents()
                    queries.clearResources()
                    queries.clearLegacyPlaylistStats()
                    // Clearing history must not resurrect old settings counters on the next read.
                    queries.upsertMetadata(LEGACY_PLAYLIST_MIGRATION_KEY, MIGRATION_COMPLETE)
                }
            }
        }
    }

    private fun resourceStat(
        resourceKey: String,
        resourceType: String,
        sourceId: String,
        sourceResourceId: String,
        title: String,
        subtitle: String,
        coverUrl: String?,
        metadataJson: String?,
        eventCount: Long,
        qualifiedPlayCount: Long,
        totalPlayedMs: Long,
        lastPlayedAt: Long?,
        contextSessionCount: Long,
    ): ListeningResourceStat = ListeningResourceStat(
        resource = resourceSnapshot(
            resourceKey = resourceKey,
            resourceType = resourceType,
            sourceId = sourceId,
            sourceResourceId = sourceResourceId,
            title = title,
            subtitle = subtitle,
            coverUrl = coverUrl,
            metadataJson = metadataJson,
        ),
        eventCount = eventCount,
        qualifiedPlayCount = qualifiedPlayCount,
        playedMs = totalPlayedMs,
        lastPlayedAtMillis = lastPlayedAt ?: 0L,
        contextSessionCount = contextSessionCount,
    )
}

private data class LegacyPlaylistBaseline(
    val sourceId: String,
    val sourceResourceId: String,
    val playCount: Long,
    val lastPlayedAtMillis: Long,
)

private fun mergePlaylistBaselines(
    actual: List<ListeningResourceStat>,
    legacy: List<LegacyPlaylistBaseline>,
    limit: Int,
): List<ListeningResourceStat> {
    val legacyByResourceId = legacy.groupBy(LegacyPlaylistBaseline::sourceResourceId)
    val merged = linkedMapOf<Pair<String, String>, ListeningResourceStat>()

    actual.forEach { stat ->
        val legacyIdentity = stat.resource
            .takeIf { it.sourceId == FALLBACK_PLAYLIST_SOURCE }
            ?.let { resource -> legacyByResourceId[resource.sourceResourceId]?.singleOrNull() }
        val normalized = if (legacyIdentity != null) {
            stat.copy(
                resource = stat.resource.copy(
                    resourceKey = playlistResourceKey(legacyIdentity.sourceId, legacyIdentity.sourceResourceId),
                    sourceId = legacyIdentity.sourceId,
                    sourceResourceId = legacyIdentity.sourceResourceId,
                ),
            )
        } else {
            stat
        }
        val key = normalized.resource.sourceId to normalized.resource.sourceResourceId
        merged[key] = merged[key]?.mergeWith(normalized) ?: normalized
    }

    legacy.forEach { baseline ->
        val key = baseline.sourceId to baseline.sourceResourceId
        val current = merged[key]
        if (current != null) {
            merged[key] = current.copy(
                contextSessionCount = current.contextSessionCount + baseline.playCount,
                lastPlayedAtMillis = maxOf(current.lastPlayedAtMillis, baseline.lastPlayedAtMillis),
            )
        } else if (baseline.playCount > 0L) {
            merged[key] = ListeningResourceStat(
                resource = ListeningResourceSnapshot(
                    resourceKey = playlistResourceKey(baseline.sourceId, baseline.sourceResourceId),
                    type = ListeningResourceType.Playlist,
                    sourceId = baseline.sourceId,
                    sourceResourceId = baseline.sourceResourceId,
                    // Legacy settings did not retain display metadata. Mine maps this identity back to
                    // the currently loaded provider playlist, which supplies the real title and cover.
                    title = baseline.sourceResourceId,
                ),
                eventCount = 0L,
                qualifiedPlayCount = 0L,
                playedMs = 0L,
                lastPlayedAtMillis = baseline.lastPlayedAtMillis,
                contextSessionCount = baseline.playCount,
            )
        }
    }

    return merged.values
        .sortedWith(
            compareByDescending<ListeningResourceStat> { it.contextSessionCount }
                .thenByDescending { it.qualifiedPlayCount }
                .thenByDescending { it.playedMs }
                .thenByDescending { it.lastPlayedAtMillis },
        )
        .take(limit)
}

private fun ListeningResourceStat.mergeWith(other: ListeningResourceStat): ListeningResourceStat = copy(
    eventCount = eventCount + other.eventCount,
    qualifiedPlayCount = qualifiedPlayCount + other.qualifiedPlayCount,
    playedMs = playedMs + other.playedMs,
    lastPlayedAtMillis = maxOf(lastPlayedAtMillis, other.lastPlayedAtMillis),
    contextSessionCount = contextSessionCount + other.contextSessionCount,
)

private fun playlistResourceKey(sourceId: String, resourceId: String): String =
    "${ListeningResourceType.Playlist.name}:${sourceId.length}:$sourceId:$resourceId"

private fun resourceSnapshot(
    resourceKey: String,
    resourceType: String,
    sourceId: String,
    sourceResourceId: String,
    title: String,
    subtitle: String,
    coverUrl: String?,
    metadataJson: String?,
) = ListeningResourceSnapshot(
    resourceKey = resourceKey,
    type = ListeningResourceType.entries.firstOrNull { it.name == resourceType } ?: ListeningResourceType.Track,
    sourceId = sourceId,
    sourceResourceId = sourceResourceId,
    title = title,
    subtitle = subtitle,
    coverUrl = coverUrl,
    metadataJson = metadataJson,
)

private fun listeningStartReason(value: String): ListeningStartReason =
    ListeningStartReason.entries.firstOrNull { it.name == value } ?: ListeningStartReason.Unknown

private fun listeningCompletionReason(value: String): ListeningCompletionReason =
    ListeningCompletionReason.entries.firstOrNull { it.name == value } ?: ListeningCompletionReason.Changed

private fun relationFor(type: ListeningResourceType): ListeningResourceRelationType = when (type) {
    ListeningResourceType.Track, ListeningResourceType.Video, ListeningResourceType.Episode, ListeningResourceType.Podcast ->
        ListeningResourceRelationType.Primary
    ListeningResourceType.Artist -> ListeningResourceRelationType.Artist
    ListeningResourceType.Album -> ListeningResourceRelationType.Album
    ListeningResourceType.Playlist -> ListeningResourceRelationType.PlaylistContext
    ListeningResourceType.Feature -> ListeningResourceRelationType.FeatureContext
    ListeningResourceType.Search -> ListeningResourceRelationType.SearchContext
    ListeningResourceType.LocalDirectory -> ListeningResourceRelationType.LocalDirectory
}
