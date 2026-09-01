package org.feeluown.mobile

/**
 * Physical media selected for one logical queue track.
 *
 * Queue identity must stay on [PlaybackState.currentTrack]. Provider replacement,
 * downloaded/local media and other physical playback choices live here instead of
 * mutating the logical [MusicTrack] stored in the queue.
 */
data class ResolvedPlaybackSource(
    val trackId: String,
    val title: String,
    val artists: String,
    val album: String,
    val source: String,
    val sourceType: TrackSourceType,
    val providerName: String? = null,
    val coverUrl: String? = null,
    val durationMs: Long? = null,
    val url: String? = null,
    val isReplacement: Boolean = false,
    val replacementStrategy: String? = null,
    val replacementScore: Double? = null,
)

fun PlaybackPayload.toResolvedPlaybackSource(
    logicalTrack: MusicTrack,
    resolveTrack: MusicTrack = logicalTrack,
    selectedReplacement: Boolean = false,
): ResolvedPlaybackSource {
    val replacement = isSmartReplacement ||
        selectedReplacement ||
        !replacementId.isNullOrBlank() ||
        resolveTrack.isSmartReplacement ||
        !resolveTrack.replacementId.isNullOrBlank()
    val partSpecificResolver = resolveTrack.id
        .takeIf { it.isNotBlank() && it != logicalTrack.id }
    return ResolvedPlaybackSource(
        trackId = if (replacement) {
            partSpecificResolver
                ?: replacementId?.takeIf { it.isNotBlank() }
                ?: resolveTrack.replacementId?.takeIf { it.isNotBlank() }
                ?: resolveTrack.id
        } else {
            resolveTrack.id
        },
        title = if (replacement) {
            resolveTrack.title.takeIf { partSpecificResolver != null && it.isNotBlank() }
                ?: replacementTitle?.takeIf { it.isNotBlank() }
                ?: resolveTrack.replacementTitle?.takeIf { it.isNotBlank() }
                ?: title.ifBlank { logicalTrack.title }
        } else {
            title.ifBlank { logicalTrack.title }
        },
        artists = if (replacement) {
            replacementArtists?.takeIf { it.isNotBlank() }
                ?: resolveTrack.replacementArtists?.takeIf { it.isNotBlank() }
                ?: artists.ifBlank { logicalTrack.artists }
        } else {
            artists.ifBlank { logicalTrack.artists }
        },
        album = if (replacement) {
            replacementAlbum?.takeIf { it.isNotBlank() }
                ?: resolveTrack.replacementAlbum?.takeIf { it.isNotBlank() }
                ?: album.ifBlank { logicalTrack.album }
        } else {
            album.ifBlank { logicalTrack.album }
        },
        source = if (replacement) {
            replacementSource?.takeIf { it.isNotBlank() }
                ?: resolveTrack.replacementSource?.takeIf { it.isNotBlank() }
                ?: source.ifBlank { resolveTrack.source }
        } else {
            source.ifBlank { resolveTrack.source }
        },
        sourceType = if (replacement) TrackSourceType.Provider else resolveTrack.sourceType,
        providerName = if (replacement) {
            replacementProviderName?.takeIf { it.isNotBlank() }
                ?: resolveTrack.replacementProviderName?.takeIf { it.isNotBlank() }
                ?: providerName?.takeIf { it.isNotBlank() }
        } else {
            providerName?.takeIf { it.isNotBlank() } ?: resolveTrack.providerName
        },
        coverUrl = if (replacement) {
            replacementCoverUrl ?: resolveTrack.replacementCoverUrl ?: coverUrl ?: logicalTrack.coverUrl
        } else {
            coverUrl ?: logicalTrack.coverUrl
        },
        durationMs = durationMs ?: resolveTrack.durationMs ?: logicalTrack.durationMs,
        url = url.takeIf { it.isNotBlank() },
        isReplacement = replacement,
        replacementStrategy = if (replacement) {
            replacementStrategy ?: resolveTrack.replacementStrategy
        } else {
            null
        },
        replacementScore = if (replacement) {
            replacementScore ?: resolveTrack.replacementScore
        } else {
            null
        },
    )
}

/** Adapts platform states that still encode resolved-source metadata on MusicTrack. */
fun MusicTrack.toLegacyResolvedPlaybackSource(): ResolvedPlaybackSource {
    val replacement = isSmartReplacement || !replacementId.isNullOrBlank()
    return ResolvedPlaybackSource(
        trackId = if (replacement) replacementId?.takeIf { it.isNotBlank() } ?: id else id,
        title = if (replacement) replacementTitle?.takeIf { it.isNotBlank() } ?: title else title,
        artists = if (replacement) replacementArtists?.takeIf { it.isNotBlank() } ?: artists else artists,
        album = if (replacement) replacementAlbum?.takeIf { it.isNotBlank() } ?: album else album,
        source = if (replacement) replacementSource?.takeIf { it.isNotBlank() } ?: source else source,
        sourceType = if (replacement) TrackSourceType.Provider else sourceType,
        providerName = if (replacement) replacementProviderName ?: providerName else providerName,
        coverUrl = if (replacement) replacementCoverUrl ?: coverUrl else coverUrl,
        durationMs = durationMs,
        isReplacement = replacement,
        replacementStrategy = replacementStrategy.takeIf { replacement },
        replacementScore = replacementScore.takeIf { replacement },
    )
}

/**
 * Converts legacy replacement-decorated tracks back to the logical queue identity.
 * Replacement fields remain on [MusicTrack] only as a compatibility input to provider resolution.
 */
fun MusicTrack.logicalPlaybackTrack(): MusicTrack {
    val hasReplacementMetadata = isSmartReplacement ||
        !originalId.isNullOrBlank() ||
        !replacementId.isNullOrBlank()
    if (!hasReplacementMetadata) return this
    val logicalId = originalId?.takeIf { it.isNotBlank() } ?: id
    val logicalSource = originalSource?.takeIf { it.isNotBlank() }
        ?: logicalId.substringBefore(':').takeIf { it.isNotBlank() }
        ?: source
    return copy(
        id = logicalId,
        title = originalTitle ?: title,
        artists = originalArtists ?: artists,
        album = originalAlbum ?: album,
        source = logicalSource,
        sourceType = TrackSourceType.Provider,
        coverUrl = originalCoverUrl ?: coverUrl,
        localUri = null,
        providerId = logicalId,
        providerName = originalProviderName,
        isSmartReplacement = false,
        originalId = null,
        originalTitle = null,
        originalArtists = null,
        originalAlbum = null,
        originalSource = null,
        originalProviderName = null,
        originalCoverUrl = null,
        replacementId = null,
        replacementTitle = null,
        replacementArtists = null,
        replacementAlbum = null,
        replacementSource = null,
        replacementProviderName = null,
        replacementCoverUrl = null,
        replacementStrategy = null,
        replacementScore = null,
    )
}

fun ResolvedPlaybackSource.toNavigationTrack(logicalTrack: MusicTrack): MusicTrack = MusicTrack(
    id = trackId,
    title = title.ifBlank { logicalTrack.title },
    artists = artists.ifBlank { logicalTrack.artists },
    album = album.ifBlank { logicalTrack.album },
    source = source,
    sourceType = sourceType,
    coverUrl = coverUrl ?: logicalTrack.coverUrl,
    durationMs = durationMs,
    providerId = trackId,
    providerName = providerName ?: source,
)
