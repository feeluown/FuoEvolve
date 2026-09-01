package org.feeluown.mobile

/**
 * Optional capability for direct-resolution engines that need both identities of one start.
 *
 * [logicalTrack] is the queue/business identity. [resolveTrack] is the concrete resolver input
 * (for example a downloaded copy, multipart item, or replacement-decorated compatibility track).
 * Implementations must keep [PlaybackState.currentTrack] logical and publish physical identity via
 * [PlaybackState.resolvedSource].
 */
interface ResolvedPlaybackSourceAwareEngine {
    fun playResolved(
        logicalTrack: MusicTrack,
        resolveTrack: MusicTrack,
        payload: PlaybackPayload,
    )
}
