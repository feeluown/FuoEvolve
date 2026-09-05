package org.feeluown.mobile

/**
 * Stable fingerprint binding the occurrence-identity sidecar to the v2 queue payload it belongs to.
 * A stale/partially-written sidecar is safer to discard than to attach occurrence ids to the wrong
 * queue ordering.
 */
fun PlaybackQueueSnapshot.queueIdentityFingerprint(): String {
    var hash = 1_125_899_906_842_597L
    PlaybackQueueCodec.encode(this).forEach { char ->
        hash = hash * 31L + char.code
    }
    return hash.toString()
}

fun PlaybackQueueSnapshot.withMatchingIdentitySnapshot(
    identity: PlaybackQueueIdentitySnapshot?,
    fingerprint: String?,
): PlaybackQueueSnapshot {
    if (identity == null || fingerprint.isNullOrBlank()) return this
    if (fingerprint != queueIdentityFingerprint()) return this
    return withIdentitySnapshot(identity)
}
