package org.feeluown.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSUserDefaults

class IosPlaybackQueueStore : PlaybackQueueStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun load(): PlaybackQueueSnapshot = withContext(Dispatchers.Default) {
        val snapshot = defaults.stringForKey(KEY_QUEUE_SNAPSHOT)
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { PlaybackQueueCodec.decode(raw) }.getOrNull() }
            ?: PlaybackQueueSnapshot()
        val identity = defaults.stringForKey(KEY_QUEUE_IDENTITY)
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { PlaybackQueueIdentityCodec.decode(raw) }.getOrNull() }
        val fingerprint = defaults.stringForKey(KEY_QUEUE_IDENTITY_FINGERPRINT)
            ?.takeIf { it.isNotBlank() }
        snapshot.withMatchingIdentitySnapshot(identity, fingerprint)
    }

    override suspend fun save(snapshot: PlaybackQueueSnapshot) {
        withContext(Dispatchers.Default) {
            defaults.setObject(PlaybackQueueCodec.encode(snapshot), KEY_QUEUE_SNAPSHOT)
            defaults.setObject(
                PlaybackQueueIdentityCodec.encode(snapshot.toIdentitySnapshot()),
                KEY_QUEUE_IDENTITY,
            )
            defaults.setObject(snapshot.queueIdentityFingerprint(), KEY_QUEUE_IDENTITY_FINGERPRINT)
            defaults.synchronize()
        }
    }

    private companion object {
        private const val KEY_QUEUE_SNAPSHOT = "playback_queue_snapshot"
        private const val KEY_QUEUE_IDENTITY = "playback_queue_identity"
        private const val KEY_QUEUE_IDENTITY_FINGERPRINT = "playback_queue_identity_fingerprint"
    }
}
