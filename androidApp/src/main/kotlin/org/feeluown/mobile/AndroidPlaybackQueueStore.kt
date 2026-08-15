package org.feeluown.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPlaybackQueueStore(context: Context) : PlaybackQueueStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var latestSnapshot: PlaybackQueueSnapshot? = null

    override suspend fun load(): PlaybackQueueSnapshot = withContext(Dispatchers.IO) {
        preferences.getString(KEY_QUEUE_SNAPSHOT, null)
            ?.let { raw -> runCatching { PlaybackQueueCodec.decode(raw) }.getOrNull() }
            ?.also { latestSnapshot = it }
            ?: PlaybackQueueSnapshot()
    }

    override suspend fun save(snapshot: PlaybackQueueSnapshot) {
        // FuoPlayerController launches save from its main-immediate scope. Capture the complete
        // snapshot before the first suspension so a later pause can synchronously flush exactly
        // this queue even if the asynchronous IO write has not finished yet.
        latestSnapshot = snapshot
        withContext(Dispatchers.IO) {
            writeSnapshot(snapshot)
        }
    }

    /**
     * Durably writes the most recent complete controller queue snapshot. This is intentionally
     * synchronous and is only used at important playback lifecycle boundaries (playing/paused),
     * where surviving an abrupt process kill is more important than deferring a small preference
     * write.
     */
    fun flushLatest() {
        latestSnapshot?.let(::writeSnapshot)
    }

    private fun writeSnapshot(snapshot: PlaybackQueueSnapshot) {
        preferences.edit()
            .putString(KEY_QUEUE_SNAPSHOT, PlaybackQueueCodec.encode(snapshot))
            .commit()
    }

    private companion object {
        private const val PREFS_NAME = "fuo_playback_queue"
        private const val KEY_QUEUE_SNAPSHOT = "queue_snapshot"
    }
}
