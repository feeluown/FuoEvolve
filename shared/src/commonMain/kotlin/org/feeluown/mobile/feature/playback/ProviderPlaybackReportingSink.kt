package org.feeluown.mobile

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private val SUPPORTED_PLAYBACK_REPORTING_PROVIDERS = setOf("netease", "bilibili", "ytmusic")
private const val PLAYBACK_REPORTING_FAILURE_FEEDBACK_COOLDOWN_MS = 15_000L

/**
 * Persists local listening history first, then mirrors eligible playback facts to the original source provider.
 * Remote reporting is serialized, best effort, and never blocks the playback/history writer.
 */
internal class ProviderPlaybackReportingSink(
    private val delegate: ListeningHistorySink,
    private val reporting: ProviderPlaybackReportingRepository,
    private val settingsRepository: AppSettingsRepository,
    private val onReportingFailure: (String) -> Unit = {},
    private val nowMillis: () -> Long = { 0L },
    scope: CoroutineScope,
) : ListeningHistorySink {
    private data class PendingReport(
        val providerId: String,
        val report: ProviderPlaybackReport,
    )

    private val pending = Channel<PendingReport>(Channel.UNLIMITED)
    private var lastFailureFeedbackKey: String? = null
    private var lastFailureFeedbackAtMillis = Long.MIN_VALUE

    init {
        scope.launch {
            for (item in pending) {
                if (!isEnabled(item.providerId)) continue
                runCatching { reporting.reportPlayback(item.providerId, item.report) }
                    .onFailure { throwable -> publishReportingFailure(item.providerId, throwable) }
            }
        }
    }

    override suspend fun upsert(record: ListeningHistoryRecord) {
        delegate.upsert(record)
        val item = record.toPendingReport() ?: return
        if (!isEnabled(item.providerId)) return
        pending.trySend(item)
    }

    private fun isEnabled(providerId: String): Boolean =
        providerId in SUPPORTED_PLAYBACK_REPORTING_PROVIDERS &&
            providerId in settingsRepository.state.value.settings.playbackReportingProviderIds

    private fun publishReportingFailure(providerId: String, throwable: Throwable) {
        val detail = throwable.providerFailureOrNull(providerId)?.userMessage
            ?: throwable.message
            ?: throwable::class.simpleName.orEmpty().ifBlank { "未知错误" }
        val message = "播放数据上报失败：$detail"
        val key = "$providerId|$message"
        val now = nowMillis()
        if (
            lastFailureFeedbackKey == key &&
            now - lastFailureFeedbackAtMillis < PLAYBACK_REPORTING_FAILURE_FEEDBACK_COOLDOWN_MS
        ) {
            return
        }
        lastFailureFeedbackKey = key
        lastFailureFeedbackAtMillis = now
        runCatching { onReportingFailure(message) }
    }

    private fun ListeningHistoryRecord.toPendingReport(): PendingReport? {
        val primary = resources.firstOrNull { it.relation == ListeningResourceRelationType.Primary }?.resource
            ?: return null
        val providerId = primary.sourceId
        if (providerId !in SUPPORTED_PLAYBACK_REPORTING_PROVIDERS) return null

        val reportKind = when (completionReason) {
            ListeningCompletionReason.Ended -> ProviderPlaybackReportKind.Completed
            ListeningCompletionReason.Changed -> ProviderPlaybackReportKind.Changed
            ListeningCompletionReason.Stopped -> ProviderPlaybackReportKind.Stopped
            ListeningCompletionReason.Error -> ProviderPlaybackReportKind.Error
            null -> if (playedMs <= 0L) ProviderPlaybackReportKind.Started else ProviderPlaybackReportKind.Progress
        }
        return PendingReport(
            providerId = providerId,
            report = ProviderPlaybackReport(
                sessionId = sessionKey,
                trackId = primary.sourceResourceId,
                playedMs = playedMs,
                durationMs = durationMs,
                startedAtMillis = startedAtMillis,
                updatedAtMillis = updatedAtMillis,
                endedAtMillis = endedAtMillis,
                kind = reportKind,
                qualified = qualified,
            ),
        )
    }
}
