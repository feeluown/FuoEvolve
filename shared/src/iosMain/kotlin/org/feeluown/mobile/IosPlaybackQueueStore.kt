package org.feeluown.mobile

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSUserDefaults

class IosPlaybackQueueStore : PlaybackQueueStore {
    private val defaults = NSUserDefaults.standardUserDefaults

    override suspend fun load(): PlaybackQueueSnapshot = withContext(Dispatchers.Default) {
        defaults.stringForKey(KEY_QUEUE_SNAPSHOT)
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { PlaybackQueueCodec.decode(raw) }.getOrNull() }
            ?: PlaybackQueueSnapshot()
    }

    override suspend fun save(snapshot: PlaybackQueueSnapshot) {
        withContext(Dispatchers.Default) {
            defaults.setObject(PlaybackQueueCodec.encode(snapshot), KEY_QUEUE_SNAPSHOT)
            defaults.synchronize()
        }
    }

    private companion object {
        private const val KEY_QUEUE_SNAPSHOT = "playback_queue_snapshot"
    }
}
