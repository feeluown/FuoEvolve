package org.feeluown.mobile

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val LEGACY_PLAYLIST_KEY_SEPARATOR = "::"

/**
 * Read-through adapter that guarantees the old settings counter is imported before any history read.
 * Recording itself stays fast and writes directly to the event store; the import is lazy, idempotent,
 * and serialized by the repository/database migration marker.
 */
internal class LegacyPlaylistStatsMigratingRepository(
    private val delegate: ListeningHistoryRepository,
    private val settingsRepository: AppSettingsRepository,
) : ListeningHistoryRepository {
    private val migrationMutex = Mutex()
    private var migrationChecked = false

    override suspend fun upsert(record: ListeningHistoryRecord) = delegate.upsert(record)

    override suspend fun migrateLegacyPlaylistStats(stats: List<ListeningLegacyPlaylistStat>) =
        delegate.migrateLegacyPlaylistStats(stats)

    override suspend fun recentEvents(
        range: ListeningTimeRange,
        limit: Int,
        resourceType: ListeningResourceType?,
    ): List<ListeningHistoryEvent> {
        ensureLegacyMigration()
        return delegate.recentEvents(range, limit, resourceType)
    }

    override suspend fun recentResources(
        range: ListeningTimeRange,
        limit: Int,
        resourceType: ListeningResourceType?,
    ): List<ListeningResourceStat> {
        ensureLegacyMigration()
        return delegate.recentResources(range, limit, resourceType)
    }

    override suspend fun topResources(
        resourceType: ListeningResourceType,
        range: ListeningTimeRange,
        limit: Int,
    ): List<ListeningResourceStat> {
        ensureLegacyMigration()
        return delegate.topResources(resourceType, range, limit)
    }

    override suspend fun insights(
        range: ListeningTimeRange,
        trendBucketMs: Long,
    ): ListeningInsights {
        ensureLegacyMigration()
        return delegate.insights(range, trendBucketMs)
    }

    override suspend fun personalization(
        range: ListeningTimeRange,
        seedLimit: Int,
        overplayQualifiedThreshold: Long,
        overplayLimit: Int,
    ): ListeningPersonalization {
        ensureLegacyMigration()
        return delegate.personalization(range, seedLimit, overplayQualifiedThreshold, overplayLimit)
    }

    override suspend fun clear() {
        migrationMutex.withLock {
            delegate.clear()
            migrationChecked = true
        }
    }

    private suspend fun ensureLegacyMigration() {
        migrationMutex.withLock {
            if (migrationChecked) return
            val settings = settingsRepository.awaitSettings()
            delegate.migrateLegacyPlaylistStats(settings.toLegacyPlaylistListeningStats())
            migrationChecked = true
        }
    }
}

internal fun AppSettings.toLegacyPlaylistListeningStats(): List<ListeningLegacyPlaylistStat> =
    normalizedPlaylistPlaybackStats(this).mapNotNull { (key, stat) ->
        val separator = key.indexOf(LEGACY_PLAYLIST_KEY_SEPARATOR)
        if (separator <= 0 || separator + LEGACY_PLAYLIST_KEY_SEPARATOR.length >= key.length) {
            return@mapNotNull null
        }
        val sourceId = key.substring(0, separator)
        val resourceId = key.substring(separator + LEGACY_PLAYLIST_KEY_SEPARATOR.length)
        if (sourceId.isBlank() || resourceId.isBlank()) return@mapNotNull null
        ListeningLegacyPlaylistStat(
            sourceId = sourceId,
            sourceResourceId = resourceId,
            playCount = stat.playCount,
            lastPlayedAtMillis = stat.lastPlayedAtMillis,
        )
    }
