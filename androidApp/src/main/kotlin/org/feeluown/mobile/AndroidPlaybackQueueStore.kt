package org.feeluown.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPlaybackQueueStore(context: Context) : PlaybackQueueStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun load(): PlaybackQueueSnapshot = withContext(Dispatchers.IO) {
        val primaryRaw = preferences.getString(KEY_QUEUE_SNAPSHOT, null)
        if (primaryRaw != null) {
            return@withContext runCatching { PlaybackQueueCodec.decode(primaryRaw) }
                .getOrElse { loadEmergencySnapshot() }
        }
        loadEmergencySnapshot()
    }

    override suspend fun save(snapshot: PlaybackQueueSnapshot) {
        withContext(Dispatchers.IO) {
            // Queue writes are relatively infrequent and must survive a process being killed
            // immediately after playback is paused. commit() makes the disk write durable before
            // this coroutine completes instead of leaving it queued behind SharedPreferences.apply().
            preferences.edit()
                .putString(KEY_QUEUE_SNAPSHOT, PlaybackQueueCodec.encode(snapshot))
                .commit()
        }
    }

    /**
     * Stores a compact, last-resort queue checkpoint synchronously. This is only used if the
     * normal queue snapshot was never written or cannot be decoded after an abrupt process kill.
     */
    fun saveEmergency(snapshot: PlaybackQueueSnapshot) {
        preferences.edit()
            .putString(KEY_EMERGENCY_QUEUE_SNAPSHOT, PlaybackQueueCodec.encode(snapshot))
            .commit()
    }

    private fun loadEmergencySnapshot(): PlaybackQueueSnapshot {
        return preferences.getString(KEY_EMERGENCY_QUEUE_SNAPSHOT, null)
            ?.let { raw -> runCatching { PlaybackQueueCodec.decode(raw) }.getOrNull() }
            ?: PlaybackQueueSnapshot()
    }

    private companion object {
        private const val PREFS_NAME = "fuo_playback_queue"
        private const val KEY_QUEUE_SNAPSHOT = "queue_snapshot"
        private const val KEY_EMERGENCY_QUEUE_SNAPSHOT = "emergency_queue_snapshot"
    }
}
