package org.feeluown.mobile

/**
 * Stable identity and provenance for a provider track that has been materialized locally.
 *
 * Download task ids intentionally remain unchanged for UI/backward compatibility. Offline assets
 * use a provider-aware id so tracks from different providers cannot overwrite each other in the
 * local asset index.
 */
data class OfflineAsset(
    val id: String,
    val providerTrackId: String,
    val providerId: String?,
    val providerName: String?,
    val source: String,
    val title: String,
    val artists: String,
    val album: String,
    val localUri: String,
    val coverUrl: String? = null,
    val durationMs: Long? = null,
    val fileSize: Long = 0,
    val audioQuality: String? = null,
    val createdAt: Long = 0,
)

fun offlineAssetId(track: MusicTrack): String = offlineAssetId(
    providerId = track.providerId,
    source = track.source,
    providerTrackId = track.id,
)

fun offlineAssetId(
    providerId: String?,
    source: String,
    providerTrackId: String,
): String {
    val providerKey = providerId?.trim().takeUnless { it.isNullOrBlank() }
        ?: source.trim().ifBlank { "provider" }
    return "${encodeOfflineAssetIdPart(providerKey)}:${encodeOfflineAssetIdPart(providerTrackId)}"
}

private fun encodeOfflineAssetIdPart(value: String): String = buildString {
    value.forEach { character ->
        when (character) {
            '%' -> append("%25")
            ':' -> append("%3A")
            '\n' -> append("%0A")
            '\r' -> append("%0D")
            else -> append(character)
        }
    }
}
