package org.feeluown.mobile

/** Provider-neutral playback facts that a concrete source may translate to its native reporting API. */
enum class ProviderPlaybackReportKind {
    Started,
    Progress,
    Completed,
    Changed,
    Stopped,
    Error,
}

data class ProviderPlaybackReport(
    val sessionId: String,
    val trackId: String,
    val playedMs: Long,
    val durationMs: Long?,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
    val endedAtMillis: Long? = null,
    val kind: ProviderPlaybackReportKind,
    val qualified: Boolean = false,
)

/** Optional capability implemented only by providers with a known playback-reporting protocol. */
interface ProviderPlaybackReportingCapability {
    suspend fun reportPlayback(report: ProviderPlaybackReport)
}

/** Application-facing dispatcher that keeps concrete provider implementations out of playback. */
interface ProviderPlaybackReportingRepository {
    suspend fun reportPlayback(providerId: String, report: ProviderPlaybackReport)
}
