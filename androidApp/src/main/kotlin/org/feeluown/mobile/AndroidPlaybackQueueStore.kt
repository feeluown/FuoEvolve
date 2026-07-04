package org.feeluown.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPlaybackQueueStore(context: Context) : PlaybackQueueStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): PlaybackQueueSnapshot = withContext(Dispatchers.IO) {
        preferences.getString(KEY_QUEUE_SNAPSHOT, null)
            ?.let { raw -> runCatching { PlaybackQueueCodec.decode(raw) }.getOrNull() }
            ?: PlaybackQueueSnapshot()
    }

    override suspend fun save(snapshot: PlaybackQueueSnapshot) {
        withContext(Dispatchers.IO) {
            preferences.edit()
                .putString(KEY_QUEUE_SNAPSHOT, PlaybackQueueCodec.encode(snapshot))
                .apply()
        }
    }

    private companion object {
        private const val PREFS_NAME = "fuo_playback_queue"
        private const val KEY_QUEUE_SNAPSHOT = "queue_snapshot"
    }
}
