package org.feeluown.mobile

enum class MediaRefType {
    Artist,
    Album,
}

/**
 * Stable cross-feature media reference. The value lives in core and carries
 * only portable identity/navigation metadata; the provider-prefixed storage
 * names are retained temporarily for Kotlin source/copy compatibility during
 * P4-C, while neutral aliases are the canonical accessors for new code.
 */
data class MediaRef(
    val id: String,
    val title: String,
    val providerId: String,
    val providerName: String,
    val type: MediaRefType,
    val coverUrl: String? = null,
    val description: String = "",
    val providerUrl: String? = null,
    val trackCount: Int? = null,
    val albumCount: Int? = null,
) {
    val sourceId: String
        get() = providerId
    val sourceName: String
        get() = providerName
    val externalUrl: String?
        get() = providerUrl
}

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
    val artistItems: List<MediaRef> = emptyList(),
    val providerUrl: String? = null,
    val providerTags: List<String> = emptyList(),
) {
    val artistRefs: List<MediaRef>
        get() = artistItems
}

const val DEFAULT_LOCAL_MUSIC_MIN_DURATION_SECONDS = 0
