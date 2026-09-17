package org.feeluown.mobile

import kotlinx.serialization.Serializable

/** Storage-oriented snapshot: no credentials, playback URLs, or live provider objects. */
@Serializable
data class MigrationPlaylist(
    val id: String,
    val title: String,
    val providerId: String,
    val providerName: String = providerId,
)

@Serializable
data class MigrationTrack(
    val id: String,
    val title: String,
    val artists: String,
    val album: String = "",
    val durationMs: Long? = null,
    val providerId: String,
)

@Serializable
data class MigrationCandidate(
    val track: MigrationTrack,
    val score: Double,
)

@Serializable
enum class MigrationPhase {
    Loading, Matching, Review, Destination, Writing, Complete, Partial, Paused,
}

@Serializable
enum class MigrationTrackStatus {
    Pending, Matched, NeedsReview, Missing, Skipped, Adding, Added, Failed, Uncertain,
}

@Serializable
data class MigrationEntry(
    /** Position is stable even when a source playlist contains duplicate tracks. */
    val position: Int,
    val source: MigrationTrack,
    val candidates: List<MigrationCandidate> = emptyList(),
    val selected: MigrationTrack? = null,
    val status: MigrationTrackStatus = MigrationTrackStatus.Pending,
    val error: String? = null,
)

@Serializable
data class PlaylistMigrationTask(
    val id: String,
    val source: MigrationPlaylist,
    val targetProviderId: String,
    val phase: MigrationPhase = MigrationPhase.Loading,
    val resumePhase: MigrationPhase? = null,
    val entries: List<MigrationEntry> = emptyList(),
    val nextOffset: Int = 0,
    val sourceLoaded: Boolean = false,
    val destination: MigrationPlaylist? = null,
    /** Set *before* the create request; an ambiguous response must never create a second playlist. */
    val creationAttempted: Boolean = false,
    val requestedDestinationName: String? = null,
    val error: String? = null,
) {
    val addedCount: Int get() = entries.count { it.status == MigrationTrackStatus.Added }
    val skippedCount: Int get() = entries.count { it.status == MigrationTrackStatus.Skipped }
    val unresolvedCount: Int get() = entries.count {
        it.status == MigrationTrackStatus.NeedsReview || it.status == MigrationTrackStatus.Missing
    }
    val failedCount: Int get() = entries.count {
        it.status == MigrationTrackStatus.Failed || it.status == MigrationTrackStatus.Uncertain
    }
}

/** Implementations must durably replace a whole task before returning from save. */
interface PlaylistMigrationStore {
    suspend fun load(): List<PlaylistMigrationTask>
    suspend fun save(task: PlaylistMigrationTask)
}

/** A page is checkpointed before the next page is requested. */
data class MigrationPage(val tracks: List<MigrationTrack>, val nextOffset: Int, val hasMore: Boolean)

/** All IDs passed to the write methods must belong to targetProviderId. */
interface PlaylistMigrationProvider {
    suspend fun loadPage(playlist: MigrationPlaylist, offset: Int): MigrationPage
    suspend fun candidates(track: MigrationTrack, targetProviderId: String): List<MigrationCandidate>
    suspend fun createPlaylist(providerId: String, name: String): MigrationPlaylist
    suspend fun targetTracks(playlist: MigrationPlaylist): Set<String>
    suspend fun addTrack(playlist: MigrationPlaylist, track: MigrationTrack): Boolean
}
