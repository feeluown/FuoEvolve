package org.feeluown.mobile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderPlaybackReportingSinkTest {
    @Test
    fun reportingIsDisabledByDefault() = runTest {
        val local = RecordingHistorySink()
        val remote = RecordingReportingRepository()
        val sink = ProviderPlaybackReportingSink(
            delegate = local,
            reporting = remote,
            settingsRepository = InMemoryAppSettingsRepository(),
            scope = backgroundScope,
        )

        sink.upsert(record(providerId = "netease"))
        runCurrent()

        assertEquals(1, local.records.size)
        assertTrue(remote.reports.isEmpty())
    }

    @Test
    fun enabledProviderReportsWithoutBlockingLocalHistory() = runTest {
        val local = RecordingHistorySink()
        val remote = RecordingReportingRepository()
        val sink = ProviderPlaybackReportingSink(
            delegate = local,
            reporting = remote,
            settingsRepository = InMemoryAppSettingsRepository(
                AppSettings(playbackReportingProviderIds = setOf("netease")),
            ),
            scope = backgroundScope,
        )

        sink.upsert(record(providerId = "netease", playedMs = 30_000L, qualified = true))
        runCurrent()

        assertEquals(1, local.records.size)
        assertEquals(1, remote.reports.size)
        assertEquals("netease", remote.reports.single().first)
        assertEquals(ProviderPlaybackReportKind.Progress, remote.reports.single().second.kind)
    }

    @Test
    fun replacementFromAnotherProviderIsNeverReported() = runTest {
        val remote = RecordingReportingRepository()
        val sink = ProviderPlaybackReportingSink(
            delegate = RecordingHistorySink(),
            reporting = remote,
            settingsRepository = InMemoryAppSettingsRepository(
                AppSettings(playbackReportingProviderIds = setOf("netease", "bilibili")),
            ),
            scope = backgroundScope,
        )

        sink.upsert(
            record(
                providerId = "netease",
                resolvedProviderId = "bilibili",
                resolvedTrackId = "bilibili:BV1replacement",
            ),
        )
        runCurrent()

        assertTrue(remote.reports.isEmpty())
    }

    @Test
    fun sameProviderResolvedTrackUsesPhysicalTrackIdentity() = runTest {
        val remote = RecordingReportingRepository()
        val sink = ProviderPlaybackReportingSink(
            delegate = RecordingHistorySink(),
            reporting = remote,
            settingsRepository = InMemoryAppSettingsRepository(
                AppSettings(playbackReportingProviderIds = setOf("netease")),
            ),
            scope = backgroundScope,
        )

        sink.upsert(
            record(
                providerId = "netease",
                resolvedProviderId = "netease",
                resolvedTrackId = "netease:456",
            ),
        )
        runCurrent()

        assertEquals("netease:456", remote.reports.single().second.trackId)
    }

    @Test
    fun unsupportedProviderRemainsNoOpEvenIfPreferenceContainsIt() = runTest {
        val remote = RecordingReportingRepository()
        val sink = ProviderPlaybackReportingSink(
            delegate = RecordingHistorySink(),
            reporting = remote,
            settingsRepository = InMemoryAppSettingsRepository(
                AppSettings(playbackReportingProviderIds = setOf("qqmusic")),
            ),
            scope = backgroundScope,
        )

        sink.upsert(record(providerId = "qqmusic"))
        runCurrent()

        assertTrue(remote.reports.isEmpty())
    }

    private fun record(
        providerId: String,
        playedMs: Long = 0L,
        qualified: Boolean = false,
        resolvedProviderId: String? = null,
        resolvedTrackId: String? = null,
    ): ListeningHistoryRecord {
        val primary = ListeningResourceSnapshot(
            resourceKey = "track:$providerId:123",
            type = ListeningResourceType.Track,
            sourceId = providerId,
            sourceResourceId = "$providerId:123",
            title = "Song",
        )
        val resources = buildList {
            add(ListeningResourceRelation(primary, ListeningResourceRelationType.Primary))
            if (resolvedProviderId != null && resolvedTrackId != null) {
                add(
                    ListeningResourceRelation(
                        ListeningResourceSnapshot(
                            resourceKey = "track:$resolvedProviderId:$resolvedTrackId",
                            type = ListeningResourceType.Track,
                            sourceId = resolvedProviderId,
                            sourceResourceId = resolvedTrackId,
                            title = "Resolved",
                        ),
                        ListeningResourceRelationType.ResolvedSource,
                    ),
                )
            }
        }
        return ListeningHistoryRecord(
            sessionKey = "session-1",
            primaryResourceKey = primary.resourceKey,
            startedAtMillis = 1_000L,
            playedMs = playedMs,
            durationMs = 180_000L,
            qualified = qualified,
            resources = resources,
            updatedAtMillis = 2_000L,
        )
    }

    private class RecordingHistorySink : ListeningHistorySink {
        val records = mutableListOf<ListeningHistoryRecord>()
        override suspend fun upsert(record: ListeningHistoryRecord) {
            records += record
        }
    }

    private class RecordingReportingRepository : ProviderPlaybackReportingRepository {
        val reports = mutableListOf<Pair<String, ProviderPlaybackReport>>()
        override suspend fun reportPlayback(providerId: String, report: ProviderPlaybackReport) {
            reports += providerId to report
        }
    }
}
