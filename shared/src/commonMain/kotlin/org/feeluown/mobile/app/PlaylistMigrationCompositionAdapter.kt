package org.feeluown.mobile

/** Adapts playback's replacement search to the migration-owned candidate contract. */
fun createPlaylistMigrationCandidateProvider(
    replacement: PlaybackReplacementProviderPort,
): PlaylistMigrationCandidateProvider = object : PlaylistMigrationCandidateProvider {
    override suspend fun candidates(
        track: MigrationTrack,
        targetProviderId: String,
    ): List<MigrationCandidate> = replacement.replacementCandidates(
        track = track.toMusicTrack(),
        smartReplacementProviderIds = setOf(targetProviderId),
        smartReplacementMinScore = 0.0,
    ).filter { it.track.source == targetProviderId }
        .map { MigrationCandidate(it.track.toMigrationTrack(), it.score) }
}
