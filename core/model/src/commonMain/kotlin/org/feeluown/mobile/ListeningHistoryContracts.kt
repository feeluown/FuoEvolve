package org.feeluown.mobile

enum class ListeningResourceType {
    Track,
    Video,
    Artist,
    Album,
    Playlist,
    Feature,
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

interface ListeningHistorySink {
    suspend fun upsert(record: ListeningHistoryRecord)
}

object NoOpListeningHistorySink : ListeningHistorySink {
    override suspend fun upsert(record: ListeningHistoryRecord) = Unit
}
