package org.feeluown.mobile

data class LocalMusicScanSettings(
    val excludedDirectoryIds: Set<String> = emptySet(),
    val minDurationSeconds: Int = DEFAULT_LOCAL_MUSIC_MIN_DURATION_SECONDS,
)

data class LocalMusicDirectory(
    val id: String,
    val name: String,
    val trackCount: Int,
    val coverUrl: String? = null,
)

fun canonicalLocalMusicDirectoryId(id: String): String? {
    return id.trim('/').takeIf { it.isNotBlank() }?.let { "$it/" }
}

fun localMusicDirectoryIdAliases(id: String): Set<String> {
    val canonical = canonicalLocalMusicDirectoryId(id) ?: return setOf(id)
    return setOf(id, canonical, canonical.removeSuffix("/"))
}

fun isLocalMusicDirectoryExcluded(directoryId: String, excludedDirectoryIds: Set<String>): Boolean {
    return excludedDirectoryIds.any { excludedId ->
        directoryId in localMusicDirectoryIdAliases(excludedId)
    }
}

data class LocalTrackMetadata(
    val title: String,
    val artists: String,
    val album: String,
)

data class MusicTrack(
    val id: String,
    val title: String,
    val artists: String,
    val album: String,
    val source: String,
    val sourceType: TrackSourceType,
    val coverUrl: String? = null,
    val durationMs: Long? = null,
    val localUri: String? = null,
    val localDirectoryId: String? = null,
    val lyrics: String? = null,
    val providerId: String? = null,
    val providerName: String? = null,
    val isSmartReplacement: Boolean = false,
    val originalId: String? = null,
    val originalTitle: String? = null,
    val originalArtists: String? = null,
    val originalAlbum: String? = null,
    val originalSource: String? = null,
    val originalProviderName: String? = null,
    val originalCoverUrl: String? = null,
    val replacementId: String? = null,
    val replacementTitle: String? = null,
    val replacementArtists: String? = null,
    val replacementAlbum: String? = null,
    val replacementSource: String? = null,
    val replacementProviderName: String? = null,
    val replacementCoverUrl: String? = null,
    val replacementStrategy: String? = null,
    val replacementScore: Double? = null,
    val isUnavailable: Boolean = false,
    val artistItemId: String? = null,
    val albumItemId: String? = null,
    val artistItems: List<ProviderMediaItem> = emptyList(),
    val providerUrl: String? = null,
    val providerTags: List<String> = emptyList(),
)
