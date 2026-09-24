package org.feeluown.mobile

internal fun selectHomeRecentTracks(
    resources: List<ListeningResourceStat>,
    enabledProviderIds: Set<String>,
    limit: Int,
): List<ListeningResourceStat> =
    resources.asSequence()
        .filter { it.resource.sourceId in enabledProviderIds }
        .take(limit)
        .toList()

internal fun ListeningResourceSnapshot.toHomeHistoryTrack(providerDisplayName: String?): MusicTrack = MusicTrack(
    id = sourceResourceId,
    title = title,
    artists = subtitle,
    album = "",
    source = sourceId,
    sourceType = TrackSourceType.Provider,
    coverUrl = coverUrl,
    providerId = sourceResourceId,
    providerName = providerDisplayName,
)
