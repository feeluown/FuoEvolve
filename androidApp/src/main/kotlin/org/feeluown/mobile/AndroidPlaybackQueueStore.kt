package org.feeluown.mobile

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidPlaybackQueueStore(context: Context) : PlaybackQueueStore {
    private val applicationContext = context.applicationContext
    private val preferences = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val playbackResumeStore = AndroidPlaybackResumeStore(applicationContext)

    @Volatile
    private var latestSnapshot: PlaybackQueueSnapshot? = null

    @Volatile
    private var loadCompleted: Boolean = false

    override suspend fun load(): PlaybackQueueSnapshot = withContext(Dispatchers.IO) {
        val persistedSnapshot = preferences.getString(KEY_QUEUE_SNAPSHOT, null)
            ?.let { raw -> runCatching { PlaybackQueueCodec.decode(raw) }.getOrNull() }
            ?: PlaybackQueueSnapshot()
        val identity = preferences.getString(KEY_QUEUE_IDENTITY, null)
            ?.let { raw -> runCatching { PlaybackQueueIdentityCodec.decode(raw) }.getOrNull() }
        val restoredSession = playbackResumeStore.load()
        val snapshot = persistedSnapshot
            .withIdentitySnapshot(identity)
            .reconcileRestoredPlayback(
                plan = restoredSession?.plan,
                currentTrack = restoredSession?.currentTrack,
            )
        latestSnapshot = snapshot
        loadCompleted = true
        snapshot
    }

    override suspend fun save(snapshot: PlaybackQueueSnapshot) {
        // The Android playback engine can publish its restored session before the controller has
        // loaded the durable queue. Ignore those bootstrap writes: otherwise an empty/default
        // controller queue can overwrite the real queue (including shuffle/repeat) during startup.
        if (!loadCompleted) return

        // FuoPlayerController launches save from its main-immediate scope. Capture the complete
        // snapshot before the first suspension so a later pause can synchronously flush exactly
        // this queue even if the asynchronous IO write has not finished yet.
        latestSnapshot = snapshot
        withContext(Dispatchers.IO) {
            writeSnapshot(snapshot)
        }
    }

    /** Durably writes the most recent complete controller queue snapshot. */
    fun flushLatest() {
        if (!loadCompleted) return
        latestSnapshot?.let(::writeSnapshot)
    }

    private fun writeSnapshot(snapshot: PlaybackQueueSnapshot) {
        preferences.edit()
            .putString(KEY_QUEUE_SNAPSHOT, PlaybackQueueCodec.encode(snapshot))
            .putString(KEY_QUEUE_IDENTITY, PlaybackQueueIdentityCodec.encode(snapshot.toIdentitySnapshot()))
            .commit()
    }

    private companion object {
        private const val PREFS_NAME = "fuo_playback_queue"
        private const val KEY_QUEUE_SNAPSHOT = "queue_snapshot"
        private const val KEY_QUEUE_IDENTITY = "queue_identity"
    }
}
